/*
 * Copyright (c) 2026 Robert von Burg <eitch@eitchnet.ch>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ch.eitchnet.luminelog.service;

import ch.eitchnet.luminelog.model.LogFileModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Bounded background batch reads, including results awaiting callback delivery. */
public final class LineLoadingService implements AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(LineLoadingService.class);

	public static final int DEFAULT_WORKERS = 2;
	public static final int DEFAULT_QUEUE_CAPACITY = 16;

	private static final AtomicInteger THREAD_IDS = new AtomicInteger();

	private final Object lock = new Object();
	private final ArrayDeque<Job> demand = new ArrayDeque<>();
	private final ArrayDeque<Job> prefetch = new ArrayDeque<>();
	private final ArrayDeque<Job> ready = new ArrayDeque<>();
	private final Set<Job> admitted = new HashSet<>();
	private final Worker[] workers;
	private final int capacity;
	private final Executor callbacks;
	private final Reader reader;
	private boolean deliveryScheduled;
	private boolean closed;

	public LineLoadingService(Executor callbacks) {
		this(DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY, callbacks);
	}

	public LineLoadingService(int workers, int capacity, Executor callbacks) {
		this(workers, capacity, callbacks, LogFileModel.Snapshot::readBatch);
	}

	LineLoadingService(int workers, int capacity, Executor callbacks, Reader reader) {
		if (workers <= 0 || capacity < 0 || capacity > Integer.MAX_VALUE - workers)
			throw new IllegalArgumentException("Workers must be positive and capacity nonnegative");
		this.capacity = capacity;
		this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
		this.reader = Objects.requireNonNull(reader, "reader");
		this.workers = new Worker[workers];
		for (int i = 0; i < workers; i++)
			this.workers[i] = new Worker();
		for (Worker worker : this.workers)
			worker.thread.start();
	}

	/** A duplicate retains only the original completion, and may promote queued prefetch to demand. */
	public boolean request(Object owner, LogFileModel.Snapshot snapshot, int index, boolean prefetch,
			Consumer<Result> completion) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(snapshot, "snapshot");
		Objects.requireNonNull(completion, "completion");
		synchronized (lock) {
			if (closed)
				return false;
			for (Job job : admitted) {
				if (job.owner == owner && job.snapshot == snapshot && job.index == index) {
					if (!prefetch && this.prefetch.remove(job))
						demand.addLast(job);
					return true;
				}
			}
			if (admitted.size() >= workers.length + capacity)
				return false;
			Worker idle = null;
			for (Worker worker : workers) {
				if (worker.job == null) {
					idle = worker;
					break;
				}
			}
			if (idle == null && demand.size() + this.prefetch.size() >= capacity)
				return false;
			Job job = new Job(owner, snapshot, index, completion);
			admitted.add(job);
			if (idle != null)
				idle.job = job;
			else
				(prefetch ? this.prefetch : demand).addLast(job);
			lock.notifyAll();
			return true;
		}
	}

	public void cancel(Object owner) {
		synchronized (lock) {
			for (Job job : new ArrayList<>(admitted)) {
				if (job.owner == owner)
					cancelJob(job);
			}
		}
	}

	public int activeJobs() {
		synchronized (lock) {
			int active = 0;
			for (Worker worker : workers) {
				if (worker.job != null)
					active++;
			}
			return active;
		}
	}

	public int queuedJobs() {
		synchronized (lock) {
			return demand.size() + prefetch.size();
		}
	}

	@Override
	public void close() {
		synchronized (lock) {
			if (closed)
				return;
			closed = true;
			for (Job job : new ArrayList<>(admitted))
				cancelJob(job);
			for (Worker worker : workers)
				worker.thread.interrupt();
			lock.notifyAll();
		}
	}

	private void cancelJob(Job job) {
		job.cancelled = true;
		job.clear();
		demand.remove(job);
		prefetch.remove(job);
		ready.remove(job);
		for (Worker worker : workers) {
			if (worker.job == job) {
				// Keep the admission and physical slot until the reader actually exits.
				worker.thread.interrupt();
				return;
			}
		}
		admitted.remove(job);
	}

	private void drainReady() {
		int remaining;
		synchronized (lock) {
			remaining = ready.size();
		}
		try {
			while (remaining-- > 0) {
				synchronized (lock) {
					Job job = ready.pollFirst();
					if (job == null)
						break;
					try {
						// Serialize cancellation with delivery, including reentrant listeners.
						job.completion.accept(job.result);
					} catch (RuntimeException error) {
						// One failed listener must not strand the other admitted results.
						logger.error("Could not publish loaded log lines", error);
					} finally {
						admitted.remove(job);
						job.clear();
					}
				}
			}
		} finally {
			synchronized (lock) {
				deliveryScheduled = false;
				// Workers schedule another bounded turn, never recursively on a direct executor.
				lock.notifyAll();
			}
		}
	}

	@FunctionalInterface
	interface Reader {
		LogFileModel.Batch read(LogFileModel.Snapshot snapshot, int index) throws IOException;
	}

	public record Result(LogFileModel.Batch batch, IOException error) { }

	private static final class Job {
		private Object owner;
		private LogFileModel.Snapshot snapshot;
		private final int index;
		private Consumer<Result> completion;
		private Result result;
		private boolean cancelled;

		private Job(Object owner, LogFileModel.Snapshot snapshot, int index, Consumer<Result> completion) {
			this.owner = owner;
			this.snapshot = snapshot;
			this.index = index;
			this.completion = completion;
		}

		private void clear() {
			owner = null;
			snapshot = null;
			completion = null;
			result = null;
		}
	}

	private final class Worker implements Runnable {
		private final Thread thread;
		private Job job;

		private Worker() {
			thread = new Thread(this, "line-loading-worker-" + THREAD_IDS.incrementAndGet());
			thread.setDaemon(true);
		}

		@Override
		public void run() {
			while (true) {
				Job current;
				LogFileModel.Snapshot snapshot;
				boolean dispatch;
				synchronized (lock) {
					while (job == null && !closed && (deliveryScheduled || ready.isEmpty())) {
						try {
							lock.wait();
						} catch (InterruptedException ignored) {
							// Cancellation does not retire a physical worker.
						}
					}
					if (closed && job == null)
						return;
					Thread.interrupted();
					dispatch = !closed && !deliveryScheduled && !ready.isEmpty();
					if (dispatch)
						deliveryScheduled = true;
					current = dispatch ? null : job;
					snapshot = current == null ? null : current.snapshot;
				}
				if (dispatch) {
					try {
						callbacks.execute(LineLoadingService.this::drainReady);
					} catch (RuntimeException e) {
						synchronized (lock) {
							for (Job pending : ready) {
								admitted.remove(pending);
								pending.clear();
							}
							ready.clear();
							deliveryScheduled = false;
							lock.notifyAll();
						}
					}
					continue;
				}
				Result result = null;
				if (snapshot != null) {
					try {
						result = new Result(reader.read(snapshot, current.index), null);
					} catch (Exception e) {
						result = new Result(null, e instanceof IOException io ? io : new IOException(e));
					}
				}
				snapshot = null;
				synchronized (lock) {
					job = demand.pollFirst();
					if (job == null)
						job = prefetch.pollFirst();
					if (!current.cancelled && !closed) {
						current.result = result;
						ready.addLast(current);
					} else {
						admitted.remove(current);
						current.clear();
					}
					lock.notifyAll();
				}
				result = null;
			}
		}
	}
}