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

import ch.eitchnet.luminelog.model.OccurrenceIndex;
import ch.eitchnet.luminelog.model.SpillableOccurrenceIndex;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class OccurrencePageService implements AutoCloseable {

	public static final int PAGE_SIZE = 4096;

	public record Result(int[] identifiers, IOException error) {
	}

	@FunctionalInterface
	interface Reader {
		int[] read(OccurrenceIndex index, int pageNumber) throws IOException;
	}

	private enum State { QUEUED, RUNNING, READY, DELIVERING, DONE }

	private final Object lock = new Object();
	private final ThreadPoolExecutor workers;
	private final Executor callbacks;
	private final Reader reader;
	private final int limit;
	private final Set<Job> jobs = new HashSet<>();
	private final ArrayDeque<Job> ready = new ArrayDeque<>();
	private final Runnable dispatcher = this::dispatch;
	private boolean dispatchPending;
	private boolean closed;

	public OccurrencePageService(Executor callbacks) {
		this(2, 16, callbacks);
	}

	public OccurrencePageService(int workers, int capacity, Executor callbacks) {
		this(workers, capacity, callbacks, OccurrencePageService::readPage);
	}

	OccurrencePageService(int workers, int capacity, Executor callbacks, Reader reader) {
		if (workers <= 0 || capacity < 0)
			throw new IllegalArgumentException("Invalid worker or queue capacity");
		this.limit = Math.addExact(workers, capacity);
		this.callbacks = Objects.requireNonNull(callbacks);
		this.reader = Objects.requireNonNull(reader);
		this.workers = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
				capacity == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(capacity), runnable -> {
			Thread thread = new Thread(runnable, "occurrence-page-loader");
			thread.setDaemon(true);
			return thread;
		});
	}

	public boolean request(Object owner, OccurrenceIndex index, int pageNumber, Consumer<Result> completion) {
		Objects.requireNonNull(owner);
		Objects.requireNonNull(index);
		Objects.requireNonNull(completion);
		if (pageNumber < 0)
			throw new IllegalArgumentException("Negative page number");
		synchronized (lock) {
			if (closed)
				return false;
			for (Job job : jobs)
				if (!job.cancelled && job.owner == owner && job.index == index && job.pageNumber == pageNumber)
					return false;
			if (jobs.size() >= limit)
				return false;
			Job job = new Job(owner, index, pageNumber, completion);
			jobs.add(job);
			try {
				workers.execute(job);
				return true;
			} catch (RejectedExecutionException e) {
				retire(job);
				return false;
			}
		}
	}

	public void cancel(Object owner) {
		synchronized (lock) {
			for (Job job : jobs.toArray(Job[]::new))
				if (job.owner == owner)
					cancelJob(job);
		}
	}

	public int queuedJobs() {
		synchronized (lock) {
			return (int) jobs.stream().filter(job -> job.state == State.QUEUED).count();
		}
	}

	public int activeJobs() {
		synchronized (lock) {
			return (int) jobs.stream().filter(job -> job.state == State.RUNNING).count();
		}
	}

	public int admittedJobs() {
		synchronized (lock) {
			return jobs.size();
		}
	}

	@Override
	public void close() {
		synchronized (lock) {
			closed = true;
			for (Job job : jobs.toArray(Job[]::new))
				cancelJob(job);
			workers.shutdownNow();
		}
	}

	private void cancelJob(Job job) {
		job.cancelled = true;
		job.clear();
		if (job.state == State.RUNNING) {
			job.thread.interrupt();
		} else if (job.state != State.DELIVERING) {
			workers.remove(job);
			ready.remove(job);
			retire(job);
		}
	}

	private void retire(Job job) {
		jobs.remove(job);
		job.state = State.DONE;
		job.clear();
	}

	private void scheduleDispatch() {
		try {
			callbacks.execute(dispatcher);
		} catch (RuntimeException e) {
			synchronized (lock) {
				while (!ready.isEmpty())
					retire(ready.removeFirst());
				dispatchPending = false;
			}
		}
	}

	private void dispatch() {
		while (true) {
			Job job;
			Consumer<Result> completion;
			Result result;
			synchronized (lock) {
				job = ready.pollFirst();
				if (job == null) {
					dispatchPending = false;
					return;
				}
				job.state = State.DELIVERING;
				completion = job.completion;
				result = job.result;
				job.result = null;
			}
			try {
				completion.accept(result);
			} catch (RuntimeException ignored) {
				// A failed consumer must not strand other owners' deliveries.
			} finally {
				synchronized (lock) {
					retire(job);
				}
			}
		}
	}

	private static int[] readPage(OccurrenceIndex index, int pageNumber) throws IOException {
		if (index instanceof SpillableOccurrenceIndex spill)
			return spill.readPage(pageNumber);
		long start = (long) pageNumber * PAGE_SIZE;
		int size = index.size();
		if (start >= size)
			return new int[0];
		int[] identifiers = new int[(int) Math.min(PAGE_SIZE, size - start)];
		for (int i = 0; i < identifiers.length; i++)
			identifiers[i] = index.lineIndex((int) start + i);
		return identifiers;
	}

	private final class Job implements Runnable {
		private Object owner;
		private OccurrenceIndex index;
		private final int pageNumber;
		private Consumer<Result> completion;
		private Result result;
		private State state = State.QUEUED;
		private Thread thread;
		private boolean cancelled;

		private Job(Object owner, OccurrenceIndex index, int pageNumber, Consumer<Result> completion) {
			this.owner = owner;
			this.index = index;
			this.pageNumber = pageNumber;
			this.completion = completion;
		}

		private void clear() {
			owner = null;
			index = null;
			completion = null;
			result = null;
		}

		@Override
		public void run() {
			OccurrenceIndex source;
			synchronized (lock) {
				if (state == State.DONE)
					return;
				state = State.RUNNING;
				thread = Thread.currentThread();
				source = index;
			}
			Result loaded;
			try {
				loaded = new Result(reader.read(source, pageNumber), null);
			} catch (IOException e) {
				loaded = new Result(null, e);
			} catch (RuntimeException e) {
				loaded = new Result(null, new IOException("Unable to read occurrence page", e));
			} catch (Error e) {
				synchronized (lock) {
					thread = null;
					retire(this);
				}
				throw e;
			}
			boolean schedule = false;
			synchronized (lock) {
				thread = null;
				if (closed || cancelled) {
					retire(this);
				} else {
					result = loaded;
					state = State.READY;
					ready.addLast(this);
					if (!dispatchPending) {
						dispatchPending = true;
						schedule = true;
					}
				}
			}
			if (schedule)
				scheduleDispatch();
		}
	}
}