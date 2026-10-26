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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class TailSchedulerTest {

	@TempDir
	Path directory;

	@Test
	void idleBackoffCapsAndChangesResetToFastPollingEvenWhenInactive() throws Exception {
		LogFileModel model = model();
		LogFileModel.Snapshot first = model.snapshot();
		LogFileModel.Snapshot second = append(model);
		AtomicReference<LogFileModel.Snapshot> current = new AtomicReference<>(first);
		List<Long> checks = new ArrayList<>();
		List<Long> updates = new ArrayList<>();
		AtomicLong clock = new AtomicLong();
		ManualPool pool = new ManualPool();
		try (TailScheduler scheduler = new TailScheduler(clock::get, pool, null)) {
			scheduler.watch(cancelled -> {
				checks.add(clock.get());
				return current.get();
			}, first.version(), snapshot -> updates.add(clock.get()), error -> fail(error));
			for (long time : new long[] {0, 100, 300, 700, 1500, 3100, 5100, 7100})
				pollAt(scheduler, pool, clock, time);
			assertEquals(List.of(0L, 100L, 300L, 700L, 1500L, 3100L, 5100L, 7100L), checks);
			current.set(second);
			pollAt(scheduler, pool, clock, 9099);
			assertTrue(updates.isEmpty());
			pollAt(scheduler, pool, clock, 9100);
			assertEquals(List.of(9100L), updates);
			pollAt(scheduler, pool, clock, 9149);
			assertEquals(9, checks.size());
			pollAt(scheduler, pool, clock, 9150);
			pollAt(scheduler, pool, clock, 9250);
			assertEquals(List.of(9100L, 9150L, 9250L), checks.subList(8, 11));
		}
	}

	@Test
	void activationAdvancesIdleDeadlineAndSelectedDetectionIsBounded() throws Exception {
		LogFileModel model = model();
		LogFileModel.Snapshot first = model.snapshot();
		LogFileModel.Snapshot second = append(model);
		AtomicReference<LogFileModel.Snapshot> current = new AtomicReference<>(first);
		AtomicLong clock = new AtomicLong();
		ManualPool pool = new ManualPool();
		List<Long> checks = new ArrayList<>();
		List<Long> updates = new ArrayList<>();
		try (TailScheduler scheduler = new TailScheduler(clock::get, pool, null)) {
			TailScheduler.Registration registration = scheduler.watch(cancelled -> {
				checks.add(clock.get());
				return current.get();
			}, first.version(), snapshot -> updates.add(clock.get()), error -> fail(error));
			for (long time : new long[] {0, 100, 300, 700, 1500, 3100})
				pollAt(scheduler, pool, clock, time);
			clock.set(3200);
			registration.setActive(true);
			pollAt(scheduler, pool, clock, 3200);
			assertEquals(3200L, checks.getLast());
			pollAt(scheduler, pool, clock, 3450);
			pollAt(scheduler, pool, clock, 3700);
			current.set(second);
			pollAt(scheduler, pool, clock, 3949);
			assertTrue(updates.isEmpty());
			pollAt(scheduler, pool, clock, 3950);
			assertEquals(List.of(3950L), updates);
			registration.setActive(false);
			pollAt(scheduler, pool, clock, 4000);
			assertEquals(4000L, checks.getLast(), "A change stays fast even after deselection");
		}
	}

	@Test
	void capturesInitialVersionBeforeWorkerRunsAndReadsOnlyOnWorker() throws Exception {
		LogFileModel model = model();
		AtomicLong clock = new AtomicLong();
		ManualPool pool = new ManualPool();
		List<Long> updates = new ArrayList<>();
		try (TailScheduler scheduler = new TailScheduler(clock::get, pool, null)) {
			scheduler.watch(model, snapshot -> updates.add(snapshot.version()), error -> fail(error));
			assertEquals(0, scheduler.queuedJobs());
			Files.writeString(model.getFilePath(), "next\n", StandardOpenOption.APPEND);
			assertEquals(1, model.snapshot().version(), "watch must not index on its caller");
			pollAt(scheduler, pool, clock, 0);
			assertEquals(List.of(2L), updates, "A change between watch and first poll must not be lost");
			pollAt(scheduler, pool, clock, 50);
			assertEquals(1, updates.size(), "Unchanged versions are not republished");
		}
	}

	@Test
	void oneInflightIncludesCallbackAndActivationDoesNotOverlapIt() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		AtomicLong clock = new AtomicLong();
		ManualPool pool = new ManualPool();
		AtomicInteger reads = new AtomicInteger();
		CountDownLatch callbackStarted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (TailScheduler scheduler = new TailScheduler(clock::get, pool, null)) {
			TailScheduler.Registration registration = scheduler.watch(cancelled -> {
				reads.incrementAndGet();
				return snapshot;
			}, 0, result -> {
				callbackStarted.countDown();
				awaitIgnoringInterrupts(release);
			}, error -> fail(error));
			scheduler.runDue();
			Thread running = Thread.ofPlatform().daemon().start(pool.removeTask());
			try {
				await(callbackStarted);
				clock.set(10_000);
				registration.setActive(true);
				for (int i = 0; i < 100; i++)
					scheduler.runDue();
				assertEquals(0, scheduler.queuedJobs());
				assertEquals(1, reads.get());
			} finally {
				release.countDown();
				join(running);
			}
			scheduler.runDue();
			assertEquals(1, scheduler.queuedJobs(), "Activation while busy gets an immediate follow-up");
			pool.runAll();
			assertEquals(2, reads.get());
		}
	}

	@Test
	void boundedAdmissionRetriesWithoutSpinningAndPreservesOldestDueOrder() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		AtomicLong clock = new AtomicLong();
		ManualPool pool = new ManualPool();
		List<Integer> reads = new ArrayList<>();
		try (TailScheduler scheduler = new TailScheduler(clock::get, pool, null)) {
			for (int i = 0; i < 20; i++) {
				int id = i;
				scheduler.watch(cancelled -> {
					reads.add(id);
					return snapshot;
				}, snapshot.version(), result -> fail("unchanged"), error -> fail(error));
			}
			scheduler.runDue();
			assertEquals(8, scheduler.queuedJobs());
			assertEquals(1, pool.rejections);
			for (int i = 0; i < 1000; i++)
				scheduler.runDue();
			assertEquals(1, pool.rejections);
			pool.runAll();
			pollAt(scheduler, pool, clock, 49);
			assertEquals(8, reads.size());
			pollAt(scheduler, pool, clock, 50);
			assertEquals(16, reads.size());
			pollAt(scheduler, pool, clock, 100);
			assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19),
					reads.subList(0, 20), "New polls cannot overtake older rejected files");
		}
	}

	@Test
	void realPoolHasTwoWorkersEightQueueSlotsAndNeverRunsOnDispatcher() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		AtomicLong clock = new AtomicLong();
		ThreadPoolExecutor pool = workers();
		CountDownLatch started = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger reading = new AtomicInteger();
		AtomicInteger maximum = new AtomicInteger();
		AtomicBoolean callerRan = new AtomicBoolean();
		Thread caller = Thread.currentThread();
		try (TailScheduler scheduler = new TailScheduler(clock::get, pool, null)) {
			for (int i = 0; i < 50; i++)
				scheduler.watch(cancelled -> {
					callerRan.compareAndSet(false, Thread.currentThread() == caller);
					maximum.accumulateAndGet(reading.incrementAndGet(), Math::max);
					started.countDown();
					awaitIgnoringInterrupts(release);
					reading.decrementAndGet();
					return snapshot;
				}, snapshot.version(), result -> fail("unchanged"), error -> fail(error));
			scheduler.runDue();
			await(started);
			assertEquals(2, scheduler.activeJobs());
			assertEquals(8, scheduler.queuedJobs());
			assertEquals(50, scheduler.registrationCount());
			assertEquals(2, maximum.get());
			assertFalse(callerRan.get());
			scheduler.close();
			assertEquals(0, scheduler.queuedJobs());
			assertEquals(0, scheduler.registrationCount());
		} finally {
			release.countDown();
			assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void slowFileDoesNotBlockAnotherWorkerOrItsCallbacks() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		ThreadPoolExecutor pool = workers();
		CountDownLatch slowStarted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch fastDelivered = new CountDownLatch(1);
		try (TailScheduler scheduler = new TailScheduler(() -> 0, pool, null)) {
			scheduler.watch(cancelled -> {
				slowStarted.countDown();
				awaitIgnoringInterrupts(release);
				return snapshot;
			}, snapshot.version(), result -> fail("unchanged"), error -> fail(error));
			scheduler.watch(cancelled -> snapshot, 0, result -> fastDelivered.countDown(), error -> fail(error));
			scheduler.runDue();
			await(slowStarted);
			await(fastDelivered);
			assertEquals(1, release.getCount(), "Fast callback completed while slow I/O is still blocked");
		} finally {
			release.countDown();
			assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void cancellationAndShutdownSuppressHeldSuccessesAndErrorsAndReleaseReferences() throws Exception {
		for (boolean shutdown : new boolean[] {false, true}) {
			for (boolean failure : new boolean[] {false, true}) {
				LogFileModel.Snapshot snapshot = model().snapshot();
				ThreadPoolExecutor pool = workers();
				CountDownLatch started = new CountDownLatch(1);
				CountDownLatch release = new CountDownLatch(1);
				AtomicReference<BooleanSupplier> cancellation = new AtomicReference<>();
				AtomicInteger callbacks = new AtomicInteger();
				try (TailScheduler scheduler = new TailScheduler(() -> 0, pool, null)) {
					TailScheduler.Registration registration = scheduler.watch(cancelled -> {
						cancellation.set(cancelled);
						started.countDown();
						awaitIgnoringInterrupts(release);
						if (failure)
							throw new IOException("late failure");
						return snapshot;
					}, 0, result -> callbacks.incrementAndGet(), error -> callbacks.incrementAndGet());
					scheduler.runDue();
					await(started);
					if (shutdown)
						scheduler.close();
					else
						registration.close();
					registration.close();
					registration.setActive(true);
					assertTrue(cancellation.get().getAsBoolean());
					assertEquals(0, scheduler.registrationCount());
					for (String name : List.of("reader", "updates", "errors")) {
						var field = registration.getClass().getDeclaredField(name);
						field.setAccessible(true);
						assertNull(field.get(registration), name + " must be released even while I/O is blocked");
					}
					release.countDown();
					scheduler.close();
					assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
					assertEquals(0, callbacks.get());
					scheduler.runDue();
					assertEquals(0, scheduler.queuedJobs());
					assertThrows(IllegalStateException.class,
							() -> scheduler.watch(cancelled -> snapshot, 0, result -> {}, error -> {}));
				} finally {
					release.countDown();
				}
			}
		}
	}

	@Test
	void queuedCancellationDoesNotStartReaderOrPublish() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		AtomicLong clock = new AtomicLong();
		ManualPool pool = new ManualPool();
		try (TailScheduler scheduler = new TailScheduler(clock::get, pool, null)) {
			TailScheduler.Registration registration = scheduler.watch(cancelled -> {
				fail("Cancelled queued reader must not run");
				return snapshot;
			}, 0, result -> fail("late update"), error -> fail(error));
			scheduler.runDue();
			Runnable alreadyDequeued = pool.removeTask();
			registration.close();
			alreadyDequeued.run();
			pool.runAll();
			assertEquals(0, scheduler.registrationCount());
		}
	}

	@Test
	void publicWatchPassesCancellationIntoModelIndexing() throws Exception {
		Path path = Files.createTempFile(directory, "blocking-", ".log");
		Files.writeString(path, "first\n");
		BlockingModel model = new BlockingModel(path);
		ThreadPoolExecutor pool = workers();
		AtomicInteger callbacks = new AtomicInteger();
		try (TailScheduler scheduler = new TailScheduler(() -> 0, pool, null)) {
			TailScheduler.Registration registration = scheduler.watch(model,
					result -> callbacks.incrementAndGet(), error -> callbacks.incrementAndGet());
			Files.writeString(path, "next\n", StandardOpenOption.APPEND);
			scheduler.runDue();
			await(model.started);
			registration.close();
			model.release.countDown();
			scheduler.close();
			assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
			assertTrue(model.cancelled.get(), "Model indexing must see the registration cancellation predicate");
			assertEquals(1, model.snapshot().version(), "Cancelled indexing must not publish a new snapshot");
			assertEquals(0, callbacks.get());
		} finally {
			model.release.countDown();
		}
	}

	@Test
	void consecutiveFailuresNotifyOnceBackOffAndResetAfterUnchangedSuccess() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		for (boolean active : new boolean[] {false, true}) {
			AtomicLong clock = new AtomicLong();
			ManualPool pool = new ManualPool();
			AtomicBoolean failing = new AtomicBoolean(true);
			List<Long> checks = new ArrayList<>();
			AtomicInteger errors = new AtomicInteger();
			List<Long> expected = active ? List.of(0L, 100L, 300L, 550L, 800L, 1050L, 1300L, 1550L)
					: List.of(0L, 100L, 300L, 700L, 1500L, 3100L, 5100L, 7100L);
			long cap = active ? TailScheduler.MAX_ACTIVE_IDLE_POLL_MILLIS : TailScheduler.MAX_IDLE_POLL_MILLIS;
			try (TailScheduler scheduler = new TailScheduler(clock::get, pool, null)) {
				TailScheduler.Registration registration = scheduler.watch(cancelled -> {
					checks.add(clock.get());
					if (failing.get())
						throw new IOException("unavailable");
					return snapshot;
				}, snapshot.version(), result -> fail("unchanged"), error -> errors.incrementAndGet());
				registration.setActive(active);
				for (long time = 0; time <= expected.getLast(); time++)
					pollAt(scheduler, pool, clock, time);
				assertEquals(expected, checks, "Failures must back off to the appropriate idle cap");
				assertEquals(1, errors.get(), "Only the first failure in an episode is reported");
				failing.set(false);
				pollAt(scheduler, pool, clock, expected.getLast() + cap);
				assertEquals(expected.size() + 1, checks.size());
				assertEquals(1, errors.get());
				failing.set(true);
				pollAt(scheduler, pool, clock, expected.getLast() + 2 * cap);
				pollAt(scheduler, pool, clock, expected.getLast() + 3 * cap);
				assertEquals(expected.size() + 3, checks.size());
				assertEquals(2, errors.get(), "An unchanged successful poll resets failure suppression");
			}
		}
	}

	@Test
	void ioAndReaderFailuresRecoverAndBothCallbackFailuresAreIsolated() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		AtomicLong clock = new AtomicLong();
		ManualPool pool = new ManualPool();
		AtomicInteger reads = new AtomicInteger();
		AtomicInteger errors = new AtomicInteger();
		AtomicInteger updates = new AtomicInteger();
		try (TailScheduler scheduler = new TailScheduler(clock::get, pool, null)) {
			scheduler.watch(cancelled -> {
				int attempt = reads.incrementAndGet();
				if (attempt == 1)
					throw new IOException("temporary");
				if (attempt == 2)
					throw new IllegalStateException("temporary reader failure");
				return snapshot;
			}, 0, result -> {
				updates.incrementAndGet();
				throw new IllegalArgumentException("bad update listener");
			}, error -> {
				errors.incrementAndGet();
				throw new IllegalArgumentException("bad error listener");
			});
			for (long time : new long[] {0, 100, 300, 350, 450})
				pollAt(scheduler, pool, clock, time);
			assertEquals(5, reads.get());
			assertEquals(1, errors.get());
			assertEquals(1, updates.get());
		}
	}

	@Test
	void oneScheduledWakeupRegardlessOfRegistrationCountAndNoneAfterClose() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		AtomicLong clock = new AtomicLong();
		ManualPool pool = new ManualPool();
		ScheduledThreadPoolExecutor dispatcher = new ScheduledThreadPoolExecutor(1);
		dispatcher.setRemoveOnCancelPolicy(true);
		CountDownLatch dispatcherBlocked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		dispatcher.execute(() -> {
			dispatcherBlocked.countDown();
			awaitIgnoringInterrupts(release);
		});
		await(dispatcherBlocked);
		try (TailScheduler scheduler = new TailScheduler(clock::get, pool, dispatcher)) {
			for (int i = 0; i < 100; i++) {
				TailScheduler.Registration registration = scheduler.watch(cancelled -> snapshot,
						snapshot.version(), result -> {}, error -> fail(error));
				registration.setActive(true);
				assertEquals(1, dispatcher.getQueue().size());
			}
			scheduler.close();
			assertEquals(0, dispatcher.getQueue().size());
		} finally {
			release.countDown();
			dispatcher.shutdownNow();
			assertTrue(dispatcher.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void defaultDispatcherAndWorkerAreDaemonAndCallbacksAreNotOnCaller() throws Exception {
		LogFileModel model = model();
		Thread caller = Thread.currentThread();
		AtomicReference<Thread> callbackThread = new AtomicReference<>();
		CountDownLatch delivered = new CountDownLatch(1);
		try (TailScheduler scheduler = new TailScheduler()) {
			scheduler.watch(model, snapshot -> {
				callbackThread.set(Thread.currentThread());
				delivered.countDown();
			}, error -> fail(error));
			Files.writeString(model.getFilePath(), "next\n", StandardOpenOption.APPEND);
			await(delivered);
			assertNotSame(caller, callbackThread.get());
			assertTrue(callbackThread.get().isDaemon());
			assertTrue(callbackThread.get().getName().startsWith("tail-worker-"));
		}
	}

	@Test
	void measuresActualAdaptiveChecksAgainstFixedPolicyForFiftyIdleFiles() throws Exception {
		long inactive = measure(false);
		long selected = measure(true);
		long fixed = 0;
		for (long time = 0; time < 60_000; time += 50)
			fixed += 50;
		assertEquals(60_000, fixed);
		assertEquals(1700, inactive);
		assertEquals(1905, selected);
		System.out.printf("TAIL_MEASUREMENT: 50 idle readers, t=[0,60000)ms, 1ms manual clock ticks, "
				+ "zero-duration reads, bounded manual FIFO queue8 drained after each dispatch, "
				+ "50ms saturation retry; fixed50ms=%d checks (1000/s), adaptive all-inactive=%d, "
				+ "adaptive one-selected=%d%n", fixed, inactive, selected);
	}

	private long measure(boolean selected) throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		AtomicLong clock = new AtomicLong();
		AtomicLong reads = new AtomicLong();
		ManualPool pool = new ManualPool();
		try (TailScheduler scheduler = new TailScheduler(clock::get, pool, null)) {
			for (int i = 0; i < 50; i++) {
				TailScheduler.Registration registration = scheduler.watch(cancelled -> {
					reads.incrementAndGet();
					return snapshot;
				}, snapshot.version(), result -> fail("idle dataset changed"), error -> fail(error));
				if (selected && i == 0)
					registration.setActive(true);
			}
			for (long time = 0; time < 60_000; time++)
				pollAt(scheduler, pool, clock, time);
			return reads.get();
		}
	}

	private LogFileModel model() throws IOException {
		Path path = Files.createTempFile(directory, "tail-", ".log");
		Files.writeString(path, "first\n");
		return new LogFileModel(path);
	}

	private static LogFileModel.Snapshot append(LogFileModel model) throws IOException {
		Files.writeString(model.getFilePath(), "next\n", StandardOpenOption.APPEND);
		model.updateIndex();
		return model.snapshot();
	}

	private static void pollAt(TailScheduler scheduler, ManualPool pool, AtomicLong clock, long time) {
		clock.set(time);
		scheduler.runDue();
		pool.runAll();
	}

	private static ThreadPoolExecutor workers() {
		return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), task -> {
			Thread thread = new Thread(task, "tail-test-worker");
			thread.setDaemon(true);
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out awaiting worker event");
	}

	private static void awaitIgnoringInterrupts(CountDownLatch latch) {
		boolean interrupted = false;
		try {
			while (true) {
				try {
					if (!latch.await(5, TimeUnit.SECONDS))
						throw new AssertionError("Timed out awaiting release");
					return;
				} catch (InterruptedException e) {
					interrupted = true;
				}
			}
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	private static void join(Thread thread) throws InterruptedException {
		thread.join(5000);
		assertFalse(thread.isAlive(), "Worker did not finish");
	}

	private static final class BlockingModel extends LogFileModel {
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicBoolean cancelled = new AtomicBoolean();

		BlockingModel(Path path) throws IOException {
			super(path);
		}

		@Override
		public synchronized void updateIndex(BooleanSupplier cancellation) throws IOException {
			if (started != null) {
				started.countDown();
				awaitIgnoringInterrupts(release);
				cancelled.set(cancellation.getAsBoolean());
			}
			super.updateIndex(cancellation);
		}
	}

	/** Holds actual tasks until the test drains the bounded executor queue; never caller-runs. */
	private static final class ManualPool extends ThreadPoolExecutor {
		int rejections;

		ManualPool() {
			super(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
		}

		@Override
		public void execute(Runnable task) {
			if (isShutdown() || !getQueue().offer(task)) {
				rejections++;
				throw new RejectedExecutionException("Manual queue full");
			}
		}

		Runnable removeTask() {
			Runnable task = getQueue().poll();
			assertNotNull(task);
			return task;
		}

		void runAll() {
			Runnable task;
			while ((task = getQueue().poll()) != null)
				task.run();
		}
	}
}