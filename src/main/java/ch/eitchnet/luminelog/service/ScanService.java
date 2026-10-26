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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, keyed work. AutoCloseable results transfer to the successful listener, or are closed. */
public final class ScanService implements AutoCloseable {

	public static final int DEFAULT_WORKERS = 2;
	public static final int DEFAULT_QUEUE_CAPACITY = 8;

	private static final AtomicInteger THREAD_IDS = new AtomicInteger();

	private final Object lock = new Object();
	private final Map<Object, Job<?>> current = new HashMap<>();
	private final Executor callbackExecutor;
	private final ThreadPoolExecutor executor;
	private boolean closed;

	public ScanService(Executor callbackExecutor) {
		this(DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY, callbackExecutor);
	}

	public ScanService(int workers, int queueCapacity, Executor callbackExecutor) {
		if (workers <= 0 || queueCapacity <= 0) {
			throw new IllegalArgumentException("Workers and queue capacity must be positive");
		}
		this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
		executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity), task -> {
					Thread thread = new Thread(task, "scan-worker-" + THREAD_IDS.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
	}

	public <T> Job<T> submit(Object key, Work<T> work, Listener<T> listener) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(work, "work");
		Objects.requireNonNull(listener, "listener");
		synchronized (lock) {
			Job<T> job = new Job<>(this, key, work, listener);
			if (closed) {
				job.state = State.REJECTED;
				release(job);
				return job;
			}
			Job<?> previous = current.put(key, job);
			if (previous != null) {
				cancelJob(previous);
			}
			notifyListener(job, new Event<>(State.QUEUED, null, null));
			// A direct callback executor may have cancelled or superseded this job.
			if (job.state != State.QUEUED || !isCurrent(job)) {
				return job;
			}
			try {
				executor.execute(job.task);
			} catch (RejectedExecutionException e) {
				job.work = null;
				job.state = State.REJECTED;
				notifyListener(job, new Event<>(State.REJECTED, null, e));
			}
			return job;
		}
	}

	public void cancel(Object key) {
		synchronized (lock) {
			Job<?> job = current.get(key);
			if (job != null) {
				cancelJob(job);
			}
		}
	}

	public int queuedJobs() {
		return executor.getQueue().size();
	}

	public int activeJobs() {
		return executor.getActiveCount();
	}

	@Override
	public void close() {
		synchronized (lock) {
			if (closed) {
				return;
			}
			closed = true;
			for (Job<?> job : new ArrayList<>(current.values())) {
				cancelJob(job);
				release(job);
			}
			executor.shutdownNow();
		}
	}

	private boolean isCurrent(Job<?> job) {
		return job.key != null && current.get(job.key) == job;
	}

	private void cancelJob(Job<?> job) {
		if (job.state == State.CANCELLED) {
			if (!isCurrent(job)) {
				release(job);
			}
			return;
		}
		// A completed job remains cancellable until its terminal callback is delivered.
		if (!isCurrent(job) && job.key == null) {
			return;
		}
		job.generation++;
		job.state = State.CANCELLED;
		discard(job);
		job.work = null;
		executor.remove(job.task);
		if (job.runner != null) {
			job.runner.interrupt();
		}
		if (isCurrent(job) && !closed) {
			notifyListener(job, new Event<>(State.CANCELLED, null, null));
		} else {
			release(job);
		}
	}

	private <T> void runWork(Job<T> job) {
		Work<T> work;
		synchronized (lock) {
			if (job.state != State.QUEUED || !isCurrent(job)) {
				return;
			}
			job.runner = Thread.currentThread();
			work = job.work;
			job.work = null;
			job.state = State.RUNNING;
			notifyListener(job, new Event<>(State.RUNNING, null, null));
			if (job.state != State.RUNNING || !isCurrent(job)) {
				job.runner = null;
				return;
			}
		}
		T result = null;
		Throwable error = null;
		try {
			result = work.run(job);
		} catch (Throwable e) {
			error = e;
		} finally {
			synchronized (lock) {
				job.runner = null;
				if (job.state == State.RUNNING && isCurrent(job)) {
					job.ownedResult = result;
					job.state = error == null ? State.SUCCEEDED : State.FAILED;
					notifyListener(job, new Event<>(job.state, result, error));
				} else {
					closeResult(result);
				}
			}
		}
	}

	private <T> void notifyListener(Job<T> job, Event<T> event) {
		long generation = job.generation;
		try {
			callbackExecutor.execute(() -> {
				synchronized (lock) {
					if (!isCurrent(job) || job.generation != generation) {
						return;
					}
					try {
						if (event.state() == State.SUCCEEDED)
							job.ownedResult = null;
						job.listener.changed(job, event);
					} finally {
						if (terminal(event.state()) && job.generation == generation) {
							release(job);
						}
					}
				}
			});
		} catch (RuntimeException e) {
			// An unavailable callback executor (or a direct listener failure) must not leak keys.
			if (terminal(event.state()) && job.generation == generation) {
				release(job);
			}
		}
	}

	private static boolean terminal(State state) {
		return state != State.QUEUED && state != State.RUNNING;
	}

	private void release(Job<?> job) {
		discard(job);
		if (isCurrent(job)) {
			current.remove(job.key);
		}
		job.key = null;
		job.work = null;
		job.listener = null;
	}

	private void discard(Job<?> job) {
		Object result = job.ownedResult;
		job.ownedResult = null;
		closeResult(result);
	}

	private void closeResult(Object result) {
		if (result instanceof AutoCloseable resource) {
			try {
				resource.close();
			} catch (Exception e) {
				System.getLogger(ScanService.class.getName()).log(System.Logger.Level.ERROR,
						"Could not dispose abandoned scan result", e);
			}
		}
	}

	public enum State {
		QUEUED, RUNNING, SUCCEEDED, CANCELLED, FAILED, REJECTED
	}

	public record Event<T>(State state, T result, Throwable error) {
	}

	@FunctionalInterface
	public interface Work<T> {
		T run(Job<T> job) throws Exception;
	}

	@FunctionalInterface
	public interface Listener<T> {
		void changed(Job<T> job, Event<T> event);
	}

	public static final class Job<T> {
		private final ScanService service;
		private final Runnable task;
		private Object key;
		private Work<T> work;
		private Listener<T> listener;
		private State state = State.QUEUED;
		private long generation;
		private Thread runner;
		private Object ownedResult;

		private Job(ScanService service, Object key, Work<T> work, Listener<T> listener) {
			this.service = service;
			this.key = key;
			this.work = work;
			this.listener = listener;
			task = () -> service.runWork(this);
		}

		public void cancel() {
			synchronized (service.lock) {
				service.cancelJob(this);
			}
		}

		public boolean isCurrent() {
			synchronized (service.lock) {
				return service.isCurrent(this);
			}
		}

		public boolean isCancelled() {
			return state() == State.CANCELLED;
		}

		public State state() {
			synchronized (service.lock) {
				return state;
			}
		}
	}
}