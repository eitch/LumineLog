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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class LineLoadingServiceTest {

	@TempDir
	Path directory;

	@Test
	void defaultReaderDeliversBatchOnlyOnCallbackExecutor() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		ManualExecutor callbacks = new ManualExecutor();
		List<LineLoadingService.Result> results = new ArrayList<>();
		Thread caller = Thread.currentThread();
		try (LineLoadingService service = new LineLoadingService(callbacks)) {
			assertTrue(service.request(this, snapshot, 0, false, result -> {
				assertSame(caller, Thread.currentThread());
				results.add(result);
			}));
			callbacks.awaitSize(1);
			assertTrue(results.isEmpty());
			callbacks.runAll();
			assertEquals(List.of("first", "second"), results.getFirst().batch().getLines());
			assertNull(results.getFirst().error());
			assertEquals(2, LineLoadingService.DEFAULT_WORKERS);
			assertEquals(16, LineLoadingService.DEFAULT_QUEUE_CAPACITY);
		}
	}

	@Test
	void boundsPhysicalWorkersAndQueueWithoutCallerRuns() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		CountDownLatch started = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		AtomicInteger delivered = new AtomicInteger();
		List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
		ManualExecutor callbacks = new ManualExecutor();
		Thread caller = Thread.currentThread();
		try (LineLoadingService service = new LineLoadingService(2, 3, callbacks, (captured, index) -> {
			threads.add(Thread.currentThread());
			reads.incrementAndGet();
			started.countDown();
			awaitIgnoringInterrupts(release, null);
			return captured.readBatch(0);
		})) {
			try {
				assertTrue(service.request(this, snapshot, 0, false, result -> delivered.incrementAndGet()));
				assertTrue(service.request(this, snapshot, 1, false, result -> delivered.incrementAndGet()));
				await(started);
				for (int i = 2; i < 200; i++)
					assertEquals(i < 5, service.request(this, snapshot, i, false, result -> delivered.incrementAndGet()));
				assertEquals(2, service.activeJobs());
				assertEquals(3, service.queuedJobs());
				assertEquals(2, reads.get());
				for (Thread worker : threads) {
					assertNotSame(caller, worker);
					assertTrue(worker.isDaemon());
					assertTrue(worker.getName().startsWith("line-loading-worker-"));
				}
			} finally {
				release.countDown();
			}
			awaitCondition(() -> service.activeJobs() == 0 && service.queuedJobs() == 0);
			callbacks.awaitSize(1);
			assertEquals(5, reads.get());
			// No delivery is needed for workers to become idle, but admission stays bounded.
			assertEquals(0, service.activeJobs());
			assertFalse(service.request(this, snapshot, 200, false, result -> fail("Rejected callback")));
			callbacks.runAll();
			assertEquals(5, delivered.get());
		}
	}

	@Test
	void heldCompletionsConsumeTheEntireDefaultAdmissionBudget() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		ManualExecutor callbacks = new ManualExecutor();
		AtomicInteger delivered = new AtomicInteger();
		try (LineLoadingService service = new LineLoadingService(callbacks)) {
			for (int i = 0; i < 18; i++) {
				assertTrue(service.request(new Object(), snapshot, 0, false, result -> {
					assertNotNull(result.batch());
					assertNull(result.error());
					delivered.incrementAndGet();
				}));
				awaitCondition(() -> service.activeJobs() == 0 && service.queuedJobs() == 0);
				callbacks.awaitSize(1);
			}
			for (int i = 18; i < 200; i++)
				assertFalse(service.request(new Object(), snapshot, 0, false, result -> fail("Rejected callback")));
			assertEquals(0, service.activeJobs());
			assertEquals(0, service.queuedJobs());
			assertEquals(0, delivered.get());
			callbacks.runAll();
			assertEquals(18, delivered.get());
			assertTrue(service.request(this, snapshot, 0, false, result -> delivered.incrementAndGet()));
			callbacks.awaitSize(1);
			callbacks.runAll();
			assertEquals(19, delivered.get());
		}
	}

	@Test
	void deduplicatesRunningQueuedAndAwaitingDeliveryWithoutAddingListeners() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		ManualExecutor callbacks = new ManualExecutor();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		AtomicInteger delivered = new AtomicInteger();
		AtomicInteger duplicateDelivered = new AtomicInteger();
		try (LineLoadingService service = new LineLoadingService(1, 1, callbacks, (captured, index) -> {
			reads.incrementAndGet();
			started.countDown();
			awaitIgnoringInterrupts(release, null);
			return captured.readBatch(0);
		})) {
			try {
				assertTrue(service.request(this, snapshot, 0, false, result -> delivered.incrementAndGet()));
				await(started);
				assertTrue(service.request(this, snapshot, 1, true, result -> delivered.incrementAndGet()));
				for (int i = 0; i < 200; i++) {
					assertTrue(service.request(this, snapshot, 0, false, result -> duplicateDelivered.incrementAndGet()));
					assertTrue(service.request(this, snapshot, 1, true, result -> duplicateDelivered.incrementAndGet()));
				}
				assertEquals(1, reads.get());
				assertEquals(1, service.queuedJobs());
			} finally {
				release.countDown();
			}
			awaitCondition(() -> service.activeJobs() == 0 && service.queuedJobs() == 0);
			callbacks.awaitSize(1);
			assertTrue(service.request(this, snapshot, 0, false, result -> duplicateDelivered.incrementAndGet()));
			assertFalse(service.request(this, snapshot, 2, false, result -> fail("Rejected callback")));
			callbacks.runAll();
			assertEquals(2, delivered.get());
			assertEquals(0, duplicateDelivered.get());
			assertEquals(2, reads.get());
		}
	}

	@Test
	void identityKeysSeparateEqualOwnersAndDifferentSnapshotVersions() throws Exception {
		LogFileModel model = model();
		LogFileModel.Snapshot first = model.snapshot();
		Files.writeString(model.getFilePath(), "\nthird", StandardOpenOption.APPEND);
		model.updateIndex();
		LogFileModel.Snapshot second = model.snapshot();
		assertNotEquals(first.version(), second.version());
		Object owner = new String("owner");
		Object equalOwner = new String("owner");
		ManualExecutor callbacks = new ManualExecutor();
		AtomicInteger delivered = new AtomicInteger();
		try (LineLoadingService service = new LineLoadingService(1, 3, callbacks, (captured, index) -> null)) {
			assertTrue(service.request(owner, first, 0, false, result -> fail("Cancelled owner")));
			callbacks.awaitSize(1);
			assertTrue(service.request(equalOwner, first, 0, false, result -> delivered.incrementAndGet()));
			awaitCondition(() -> service.activeJobs() == 0);
			assertTrue(service.request(owner, second, 0, false, result -> fail("Cancelled owner")));
			awaitCondition(() -> service.activeJobs() == 0);
			callbacks.awaitSize(1);
			service.cancel(owner);
			callbacks.runAll();
			assertEquals(1, delivered.get());
		}
	}

	@Test
	void demandPrecedesPrefetchAndDuplicateDemandPromotesQueuedPrefetch() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<Integer> order = Collections.synchronizedList(new ArrayList<>());
		ManualExecutor callbacks = new ManualExecutor();
		AtomicInteger delivered = new AtomicInteger();
		try (LineLoadingService service = new LineLoadingService(1, 4, callbacks, (captured, index) -> {
			order.add(index);
			if (index == 0) {
				started.countDown();
				awaitIgnoringInterrupts(release, null);
			}
			return null;
		})) {
			try {
				assertTrue(service.request(this, snapshot, 0, false, result -> delivered.incrementAndGet()));
				await(started);
				assertTrue(service.request(this, snapshot, 1, true, result -> delivered.incrementAndGet()));
				assertTrue(service.request(this, snapshot, 2, true, result -> delivered.incrementAndGet()));
				assertTrue(service.request(this, snapshot, 3, false, result -> delivered.incrementAndGet()));
				assertTrue(service.request(this, snapshot, 2, false, result -> fail("Duplicate callback")));
				assertTrue(service.request(this, snapshot, 4, true, result -> delivered.incrementAndGet()));
				assertEquals(4, service.queuedJobs());
			} finally {
				release.countDown();
			}
			awaitCondition(() -> service.activeJobs() == 0 && service.queuedJobs() == 0);
			callbacks.awaitSize(1);
			assertEquals(List.of(0, 3, 2, 1, 4), order);
			callbacks.runAll();
			assertEquals(5, delivered.get());
		}
	}

	@Test
	void cancellationRemovesQueuedWorkButDoesNotFreeAnInterruptIgnoringWorker() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		Object owner = new Object();
		Object other = new Object();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<Integer> reads = Collections.synchronizedList(new ArrayList<>());
		ManualExecutor callbacks = new ManualExecutor();
		AtomicInteger delivered = new AtomicInteger();
		try (LineLoadingService service = new LineLoadingService(1, 1, callbacks, (captured, index) -> {
			reads.add(index);
			if (index == 0) {
				started.countDown();
				awaitIgnoringInterrupts(release, interrupted);
			}
			return null;
		})) {
			try {
				assertTrue(service.request(owner, snapshot, 0, false, result -> fail("Cancelled running callback")));
				await(started);
				assertTrue(service.request(owner, snapshot, 1, true, result -> fail("Cancelled queued callback")));
				service.cancel(owner);
				await(interrupted);
				assertEquals(0, service.queuedJobs());
				assertEquals(1, service.activeJobs());
				assertTrue(service.request(other, snapshot, 2, false, result -> delivered.incrementAndGet()));
				assertFalse(service.request(other, snapshot, 3, false, result -> fail("Rejected callback")));
				assertEquals(List.of(0), reads);
			} finally {
				release.countDown();
			}
			callbacks.awaitSize(1);
			assertEquals(List.of(0, 2), reads);
			callbacks.runAll();
			assertEquals(1, delivered.get());
		}
	}

	@Test
	void cancelledPendingDeliveryCannotReachANewRequestWithTheSameKey() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		ManualExecutor callbacks = new ManualExecutor();
		AtomicInteger delivered = new AtomicInteger();
		try (LineLoadingService service = new LineLoadingService(1, 0, callbacks)) {
			assertTrue(service.request(this, snapshot, 0, false, result -> fail("Stale delivery")));
			callbacks.awaitSize(1);
			service.cancel(this);
			assertTrue(service.request(this, snapshot, 0, false, result -> delivered.incrementAndGet()));
			awaitCondition(() -> service.activeJobs() == 0);
			callbacks.awaitSize(1);
			callbacks.runAll();
			assertEquals(1, delivered.get());
		}
	}

	@Test
	void repeatedCancellationKeepsOnlyOneHeldDispatcher() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		ManualExecutor callbacks = new ManualExecutor();
		AtomicInteger reads = new AtomicInteger();
		AtomicInteger delivered = new AtomicInteger();
		try (LineLoadingService service = new LineLoadingService(1, 0, callbacks, (captured, index) -> {
			reads.incrementAndGet();
			return captured.readBatch(0);
		})) {
			for (int i = 0; i < 200; i++) {
				Object owner = new Object();
				assertTrue(service.request(owner, snapshot, 0, false, result -> fail("Cancelled listener")));
				int expectedReads = i + 1;
				awaitCondition(() -> reads.get() == expectedReads && service.activeJobs() == 0);
				callbacks.awaitSize(1);
				synchronized (privateField(service, "lock")) {
					Queue<?> ready = (Queue<?>) privateField(service, "ready");
					Object pending = ready.element();
					assertNotNull(privateField(pending, "result"));
					assertNotNull(privateField(pending, "completion"));
					service.cancel(owner);
					assertTrue(ready.isEmpty());
					assertTrue(((Set<?>) privateField(service, "admitted")).isEmpty());
					for (String field : List.of("owner", "snapshot", "result", "completion"))
						assertNull(privateField(pending, field));
				}
				callbacks.assertOnlyReferences(service);
				assertEquals(0, service.queuedJobs());
			}
			assertTrue(service.request(this, snapshot, 0, false, result -> delivered.incrementAndGet()));
			awaitCondition(() -> reads.get() == 201 && service.activeJobs() == 0);
			callbacks.awaitSize(1);
			callbacks.runAll();
			assertEquals(1, delivered.get());
		}
	}

	@Test
	void reentrantRequestsUseAnotherBoundedDeliveryTurn() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		ManualExecutor callbacks = new ManualExecutor();
		AtomicInteger delivered = new AtomicInteger();
		Thread caller = Thread.currentThread();
		try (LineLoadingService service = new LineLoadingService(1, 1, callbacks)) {
			assertTrue(service.request(this, snapshot, 0, false, result -> {
				assertSame(caller, Thread.currentThread());
				delivered.incrementAndGet();
				service.cancel(this);
				assertTrue(service.request(this, snapshot, 0, false, next -> {
					assertSame(caller, Thread.currentThread());
					delivered.incrementAndGet();
				}));
			}));
			callbacks.awaitSize(1);
			callbacks.runNext();
			assertEquals(1, delivered.get());
			callbacks.awaitSize(1);
			callbacks.runNext();
			assertEquals(2, delivered.get());
		}
	}

	@Test
	void directExecutorHandlesLongReentrantRequestChainsWithoutRecursiveDispatch() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		AtomicInteger delivered = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch finished = new CountDownLatch(1);
		try (LineLoadingService service = new LineLoadingService(1, 1, Runnable::run, (captured, index) -> null)) {
			class Completion implements Consumer<LineLoadingService.Result> {
				@Override
				public void accept(LineLoadingService.Result result) {
					try {
						if (delivered.incrementAndGet() == 2000) {
							finished.countDown();
							return;
						}
						assertTrue(service.request(this, snapshot, delivered.get(), false, this));
					} catch (Throwable e) {
						failure.set(e);
						finished.countDown();
					}
				}
			}
			Completion completion = new Completion();
			assertTrue(service.request(completion, snapshot, 0, false, completion));
			await(finished);
			assertNull(failure.get());
			assertEquals(2000, delivered.get());
		}
	}

	@Test
	void throwingListenerDoesNotStrandOtherCoalescedResults() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		ManualExecutor callbacks = new ManualExecutor();
		AtomicInteger delivered = new AtomicInteger();
		try (LineLoadingService service = new LineLoadingService(1, 1, callbacks)) {
			assertTrue(service.request(this, snapshot, 0, false, result -> {
				throw new IllegalStateException("listener failed");
			}));
			callbacks.awaitSize(1);
			assertTrue(service.request(this, snapshot, 1, false, result -> delivered.incrementAndGet()));
			awaitCondition(() -> service.activeJobs() == 0);
			callbacks.awaitSize(1);
			callbacks.runNext();
			assertEquals(1, delivered.get());
			assertTrue(service.request(this, snapshot, 0, false, result -> delivered.incrementAndGet()));
			callbacks.awaitSize(1);
			callbacks.runAll();
			assertEquals(2, delivered.get());
		}
	}

	@Test
	void closeSuppressesPendingRunningAndQueuedCallbacksAndIsIdempotent() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ManualExecutor callbacks = new ManualExecutor();
		List<Integer> reads = Collections.synchronizedList(new ArrayList<>());
		try (LineLoadingService service = new LineLoadingService(1, 2, callbacks, (captured, index) -> {
			reads.add(index);
			if (index == 1) {
				started.countDown();
				awaitIgnoringInterrupts(release, interrupted);
			}
			return null;
		})) {
			try {
				assertTrue(service.request(this, snapshot, 0, false, result -> fail("Closed delivery")));
				callbacks.awaitSize(1);
				assertTrue(service.request(this, snapshot, 1, false, result -> fail("Closed delivery")));
				await(started);
				assertTrue(service.request(this, snapshot, 2, true, result -> fail("Closed delivery")));
				service.close();
				service.close();
				await(interrupted);
				assertEquals(0, service.queuedJobs());
				assertEquals(1, service.activeJobs());
				assertFalse(service.request(this, snapshot, 3, false, result -> fail("Closed delivery")));
				callbacks.runAll();
			} finally {
				release.countDown();
			}
			awaitCondition(() -> service.activeJobs() == 0);
			callbacks.runAll();
			assertEquals(List.of(0, 1), reads);
		}
	}

	@Test
	void convertsReaderExceptionsAndKeepsWorkerUsable() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		IOException expected = new IOException("read failed");
		IllegalStateException unexpected = new IllegalStateException("decode failed");
		ManualExecutor callbacks = new ManualExecutor();
		List<LineLoadingService.Result> results = new ArrayList<>();
		try (LineLoadingService service = new LineLoadingService(1, 0, callbacks, (captured, index) -> {
			if (index == 0)
				throw expected;
			if (index == 1)
				throw unexpected;
			return captured.readBatch(0);
		})) {
			for (int i = 0; i < 3; i++) {
				assertTrue(service.request(this, snapshot, i, false, results::add));
				callbacks.awaitSize(1);
				callbacks.runAll();
			}
			assertSame(expected, results.get(0).error());
			assertNull(results.get(0).batch());
			assertSame(unexpected, results.get(1).error().getCause());
			assertNull(results.get(1).batch());
			assertNull(results.get(2).error());
			assertNotNull(results.get(2).batch());
		}
	}

	@Test
	void rejectedCallbackDispatchAndThrowingDirectListenerReleaseAdmission() throws Exception {
		LogFileModel.Snapshot snapshot = model().snapshot();
		CountDownLatch rejected = new CountDownLatch(1);
		try (LineLoadingService service = new LineLoadingService(1, 0, task -> {
			rejected.countDown();
			throw new RejectedExecutionException("closed callback executor");
		})) {
			assertTrue(service.request(this, snapshot, 0, false, result -> fail("Rejected dispatch")));
			await(rejected);
			awaitCondition(() -> service.request(this, snapshot, 1, false, result -> fail("Rejected dispatch")));
		}
		AtomicInteger delivered = new AtomicInteger();
		AtomicReference<Thread> callbackThread = new AtomicReference<>();
		CountDownLatch first = new CountDownLatch(1);
		try (LineLoadingService service = new LineLoadingService(1, 0, Runnable::run)) {
			assertTrue(service.request(this, snapshot, 0, false, result -> {
				callbackThread.set(Thread.currentThread());
				first.countDown();
				throw new IllegalStateException("listener failed");
			}));
			await(first);
			awaitCondition(() -> service.request(this, snapshot, 1, false, result -> delivered.incrementAndGet()));
			awaitCondition(() -> delivered.get() == 1);
			assertNotSame(Thread.currentThread(), callbackThread.get());
		}
	}

	@Test
	void validatesConstructionArguments() {
		assertThrows(IllegalArgumentException.class, () -> new LineLoadingService(0, 1, Runnable::run));
		assertThrows(IllegalArgumentException.class, () -> new LineLoadingService(1, -1, Runnable::run));
		assertThrows(IllegalArgumentException.class, () -> new LineLoadingService(1, Integer.MAX_VALUE, Runnable::run));
		assertThrows(NullPointerException.class, () -> new LineLoadingService(null));
		assertThrows(NullPointerException.class, () -> new LineLoadingService(1, 1, Runnable::run, null));
	}

	private LogFileModel model() throws IOException {
		Path file = directory.resolve("input.log");
		Files.writeString(file, "first\nsecond");
		return new LogFileModel(file);
	}

	private static Object privateField(Object target, String name) throws ReflectiveOperationException {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out awaiting latch");
	}

	private static void awaitIgnoringInterrupts(CountDownLatch release, CountDownLatch interrupted) throws IOException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (true) {
			try {
				if (!release.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
					throw new IOException("Timed out awaiting release");
				return;
			} catch (InterruptedException e) {
				if (interrupted != null)
					interrupted.countDown();
			}
		}
	}

	private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, "Timed out awaiting condition");
			Thread.sleep(1);
		}
	}

	private static final class ManualExecutor implements Executor {
		private final Queue<Runnable> tasks = new ArrayDeque<>();

		@Override
		public synchronized void execute(Runnable task) {
			tasks.add(task);
			notifyAll();
		}

		private synchronized void awaitSize(int size) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (tasks.size() < size) {
				long remaining = deadline - System.nanoTime();
				assertTrue(remaining > 0, "Timed out awaiting callback tasks");
				TimeUnit.NANOSECONDS.timedWait(this, remaining);
			}
			assertEquals(size, tasks.size());
		}

		private void runAll() {
			while (true) {
				Runnable task;
				synchronized (this) {
					task = tasks.poll();
				}
				if (task == null)
					return;
				task.run();
			}
		}

		private void runNext() {
			Runnable task;
			synchronized (this) {
				task = tasks.remove();
			}
			task.run();
		}

		private synchronized void assertOnlyReferences(LineLoadingService service) throws IllegalAccessException {
			assertEquals(1, tasks.size());
			Runnable task = tasks.element();
			for (Field field : task.getClass().getDeclaredFields()) {
				field.setAccessible(true);
				assertSame(service, field.get(task), "Dispatcher must not capture individual jobs");
			}
		}
	}
}