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

import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Adaptive, bounded tail indexing without a UI dependency. Callbacks run on workers and count as
 * in-flight work. Consumers must gate any subsequently queued UI publication themselves.
 * Cancellation is cooperative: an already executing callback or noninterruptible I/O may finish,
 * but cancelled work cannot publish a later result or schedule another poll.
 */
public final class TailScheduler implements AutoCloseable {

	public static final long ACTIVE_POLL_MILLIS = 50;
	public static final long INITIAL_IDLE_POLL_MILLIS = 100;
	public static final long MAX_IDLE_POLL_MILLIS = 2000;
	public static final long MAX_ACTIVE_IDLE_POLL_MILLIS = 250;
	public static final int DEFAULT_WORKERS = 2;
	public static final int DEFAULT_QUEUE_CAPACITY = 8;

	private static final AtomicInteger THREAD_IDS = new AtomicInteger();
	private static final ScheduledThreadPoolExecutor DISPATCHER = createDispatcher();

	private final Object lock = new Object();
	private final LongSupplier clock;
	private final ThreadPoolExecutor workers;
	private final ScheduledThreadPoolExecutor dispatcher;
	private final Set<Registration> registrations = new HashSet<>();
	private final PriorityQueue<Registration> due = new PriorityQueue<>(Comparator
			.comparingLong((Registration registration) -> registration.dueMillis)
			.thenComparingLong(registration -> registration.order));
	private ScheduledFuture<?> wakeup;
	private long wakeupAt;
	private long wakeupGeneration;
	private long order;
	private long admissionNotBefore = Long.MIN_VALUE;
	private boolean closed;

	public TailScheduler() {
		this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), createWorkers(), DISPATCHER);
	}

	/** Null dispatcher enables deterministic, explicitly driven runDue() tests. Owns workers only. */
	TailScheduler(LongSupplier clock, ThreadPoolExecutor workers, ScheduledThreadPoolExecutor dispatcher) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.workers = Objects.requireNonNull(workers, "workers");
		this.dispatcher = dispatcher;
	}

	private static ScheduledThreadPoolExecutor createDispatcher() {
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
			Thread thread = new Thread(task, "tail-dispatcher");
			thread.setDaemon(true);
			return thread;
		});
		executor.setRemoveOnCancelPolicy(true);
		return executor;
	}

	private static ThreadPoolExecutor createWorkers() {
		return new ThreadPoolExecutor(DEFAULT_WORKERS, DEFAULT_WORKERS, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY), task -> {
			Thread thread = new Thread(task, "tail-worker-" + THREAD_IDS.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	}

	/** Captures only the current snapshot on the caller; all indexing happens on workers. */
	public Registration watch(LogFileModel model, Consumer<LogFileModel.Snapshot> updates,
			Consumer<IOException> errors) {
		Objects.requireNonNull(model, "model");
		long version = model.snapshot().version();
		return watch(cancelled -> {
			model.updateIndex(cancelled);
			return model.snapshot();
		}, version, updates, errors);
	}

	@FunctionalInterface
	interface Reader {
		LogFileModel.Snapshot read(BooleanSupplier cancelled) throws IOException;
	}

	Registration watch(Reader reader, long version, Consumer<LogFileModel.Snapshot> updates,
			Consumer<IOException> errors) {
		Objects.requireNonNull(reader, "reader");
		Objects.requireNonNull(updates, "updates");
		Objects.requireNonNull(errors, "errors");
		synchronized (lock) {
			if (closed)
				throw new IllegalStateException("Tail scheduler is closed");
			Registration registration = new Registration(reader, version, updates, errors);
			registrations.add(registration);
			enqueue(registration, clock.getAsLong());
			arm();
			return registration;
		}
	}

	public int activeJobs() {
		return workers.getActiveCount();
	}

	public int queuedJobs() {
		return workers.getQueue().size();
	}

	public int registrationCount() {
		synchronized (lock) {
			return registrations.size();
		}
	}

	private void enqueue(Registration registration, long when) {
		registration.dueMillis = when;
		registration.order = order++;
		due.add(registration);
	}

	/** One dispatch pass; rejected oldest work keeps its place and retries no sooner than 50ms. */
	void runDue() {
		synchronized (lock) {
			long now = clock.getAsLong();
			while (!closed && now >= admissionNotBefore && !due.isEmpty() && due.peek().dueMillis <= now) {
				Registration registration = due.remove();
				registration.inFlight = true;
				try {
					workers.execute(registration.task);
				} catch (RejectedExecutionException e) {
					registration.inFlight = false;
					due.add(registration);
					admissionNotBefore = now + ACTIVE_POLL_MILLIS;
					break;
				}
			}
			arm();
		}
	}

	private void arm() {
		if (dispatcher == null)
			return;
		if (closed || due.isEmpty()) {
			cancelWakeup();
			return;
		}
		long when = Math.max(due.peek().dueMillis, admissionNotBefore);
		if (wakeup != null && wakeupAt == when)
			return;
		cancelWakeup();
		long generation = wakeupGeneration;
		wakeupAt = when;
		wakeup = dispatcher.schedule(() -> {
			synchronized (lock) {
				if (closed || generation != wakeupGeneration)
					return;
				wakeup = null;
				runDue();
			}
		}, Math.max(0, when - clock.getAsLong()), TimeUnit.MILLISECONDS);
	}

	private void cancelWakeup() {
		wakeupGeneration++;
		if (wakeup != null) {
			wakeup.cancel(false);
			wakeup = null;
		}
	}

	private void poll(Registration registration) {
		Reader reader;
		synchronized (lock) {
			if (registration.cancelled)
				return;
			registration.runner = Thread.currentThread();
			reader = registration.reader;
		}
		boolean changed = false;
		try {
			LogFileModel.Snapshot snapshot = reader.read(() -> registration.cancelled);
			Consumer<LogFileModel.Snapshot> listener;
			synchronized (lock) {
				if (registration.cancelled)
					return;
				registration.failureReported = false;
				changed = snapshot.version() != registration.version;
				registration.version = snapshot.version();
				listener = changed ? registration.updates : null;
			}
			if (listener != null)
				notifySafely(listener, snapshot);
		} catch (IOException e) {
			notifyError(registration, e);
		} catch (RuntimeException e) {
			notifyError(registration, new IOException("Tail indexing failed", e));
		} finally {
			synchronized (lock) {
				registration.runner = null;
				registration.inFlight = false;
				if (!registration.cancelled) {
					long cap = registration.active ? MAX_ACTIVE_IDLE_POLL_MILLIS : MAX_IDLE_POLL_MILLIS;
					registration.idleMillis = changed ? 0 : Math.min(cap,
							Math.max(INITIAL_IDLE_POLL_MILLIS, registration.idleMillis * 2));
					long delay = changed ? ACTIVE_POLL_MILLIS : registration.idleMillis;
					if (registration.activationRequested)
						delay = 0;
					registration.activationRequested = false;
					enqueue(registration, clock.getAsLong() + delay);
					arm();
				}
			}
		}
	}

	private void notifyError(Registration registration, IOException error) {
		Consumer<IOException> listener;
		synchronized (lock) {
			if (registration.cancelled || registration.failureReported)
				return;
			registration.failureReported = true;
			listener = registration.errors;
		}
		if (listener != null)
			notifySafely(listener, error);
	}

	private static <T> void notifySafely(Consumer<T> listener, T value) {
		try {
			listener.accept(value);
		} catch (RuntimeException ignored) {
			// A failing consumer must not stop polling this or other registrations.
		}
	}

	@Override
	public void close() {
		synchronized (lock) {
			if (closed)
				return;
			closed = true;
			for (Registration registration : Set.copyOf(registrations))
				cancel(registration);
			cancelWakeup();
			workers.shutdownNow();
		}
	}

	private void cancel(Registration registration) {
		registration.cancelled = true;
		due.remove(registration);
		registrations.remove(registration);
		registration.reader = null;
		registration.updates = null;
		registration.errors = null;
		workers.remove(registration.task);
		if (registration.runner != null)
			registration.runner.interrupt();
	}

	public final class Registration implements AutoCloseable {
		private Reader reader;
		private Consumer<LogFileModel.Snapshot> updates;
		private Consumer<IOException> errors;
		private final Runnable task = () -> poll(this);
		private volatile boolean cancelled;
		private Thread runner;
		private boolean inFlight;
		private boolean active;
		private boolean activationRequested;
		private boolean failureReported;
		private long version;
		private long dueMillis;
		private long order;
		private long idleMillis;

		private Registration(Reader reader, long version, Consumer<LogFileModel.Snapshot> updates,
				Consumer<IOException> errors) {
			this.reader = reader;
			this.version = version;
			this.updates = updates;
			this.errors = errors;
		}

		/** Selecting a registration advances its next poll, but never overlaps existing work. */
		public void setActive(boolean active) {
			synchronized (lock) {
				if (cancelled || this.active == active)
					return;
				this.active = active;
				if (active) {
					idleMillis = Math.min(idleMillis, MAX_ACTIVE_IDLE_POLL_MILLIS);
					if (inFlight) {
						activationRequested = true;
					} else {
						due.remove(this);
						enqueue(this, Math.min(dueMillis, clock.getAsLong()));
					}
				}
				arm();
			}
		}

		@Override
		public void close() {
			synchronized (lock) {
				if (cancelled)
					return;
				cancel(this);
				arm();
			}
		}
	}
}