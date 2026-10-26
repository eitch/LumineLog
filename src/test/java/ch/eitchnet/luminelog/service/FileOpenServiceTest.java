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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static ch.eitchnet.luminelog.service.ScanService.State.*;
import static org.junit.jupiter.api.Assertions.*;

class FileOpenServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void opensRealModelAndDeliversStatesOnlyOnCallbacks() throws Exception {
		Path path = Files.writeString(tempDir.resolve("real.log"), "first\nsecond\n");
		ManualExecutor callbacks = new ManualExecutor();
		List<ScanService.Event<LogFileModel>> events = new ArrayList<>();
		Thread callbackThread = Thread.currentThread();
		try (FileOpenService service = new FileOpenService(callbacks)) {
			ScanService.Job<LogFileModel> job = service.open("owner", path, (current, event) -> {
				assertSame(callbackThread, Thread.currentThread());
				assertTrue(current.isCurrent());
				events.add(event);
			});
			callbacks.awaitSize(3);
			assertTrue(events.isEmpty());
			assertEquals(SUCCEEDED, job.state());
			callbacks.runAll();
			assertEquals(List.of(QUEUED, RUNNING, SUCCEEDED), states(events));
			assertEquals(path, events.getLast().result().getFilePath());
			assertEquals(List.of("first", "second", ""), events.getLast().result().readBatch(0).getLines());
			assertReleased(job);
		}
	}

	@Test
	void boundsWorkersAndQueueUnderBurstLoad() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		CountDownLatch started = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		List<ScanService.Job<LogFileModel>> jobs = new ArrayList<>();
		List<ScanService.Event<LogFileModel>> events = new ArrayList<>();
		try (FileOpenService service = new FileOpenService(2, 8, callbacks, (path, cancelled) -> {
			calls.incrementAndGet();
			started.countDown();
			awaitIgnoringInterrupts(release);
			return null;
		})) {
			jobs.add(service.open(0, tempDir, (job, event) -> events.add(event)));
			jobs.add(service.open(1, tempDir, (job, event) -> events.add(event)));
			await(started);
			for (int i = 2; i < 100; i++)
				jobs.add(service.open(i, tempDir, (job, event) -> events.add(event)));
			assertEquals(2, service.activeJobs());
			assertEquals(8, service.queuedJobs());
			assertEquals(2, calls.get());
			assertEquals(90, jobs.stream().filter(job -> job.state() == REJECTED).count());
			callbacks.runAll();
			assertEquals(90, events.stream().filter(event -> event.state() == REJECTED).count());
			for (var event : events)
				if (event.state() == REJECTED)
					assertInstanceOf(RejectedExecutionException.class, event.error());
		} finally {
			release.countDown();
		}
		assertEquals(2, calls.get(), "Queued reads must not run after close");
		for (var job : jobs)
			assertReleased(job);
		assertEquals(2, FileOpenService.DEFAULT_WORKERS);
		assertEquals(8, FileOpenService.DEFAULT_QUEUE_CAPACITY);
	}

	@Test
	void coalescesQueuedOwnerAndCancellationImmediatelyFreesQueue() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<Path> reads = new ArrayList<>();
		List<ScanService.Event<LogFileModel>> staleEvents = new ArrayList<>();
		List<ScanService.Event<LogFileModel>> cancelledEvents = new ArrayList<>();
		Path blocker = tempDir.resolve("blocker");
		Path latest = tempDir.resolve("latest");
		try (FileOpenService service = new FileOpenService(1, 1, callbacks, (path, cancelled) -> {
			reads.add(path);
			if (path.equals(blocker)) {
				started.countDown();
				awaitIgnoringInterrupts(release);
			}
			return null;
		})) {
			service.open("blocker", blocker, (job, event) -> { });
			await(started);
			var stale = service.open("owner", tempDir.resolve("stale"), (job, event) -> staleEvents.add(event));
			var cancelled = service.open("owner", tempDir.resolve("cancelled"),
					(job, event) -> cancelledEvents.add(event));
			assertEquals(CANCELLED, stale.state());
			assertEquals(1, service.queuedJobs());
			service.cancel("owner");
			assertEquals(0, service.queuedJobs());
			var next = service.open("owner", latest, (job, event) -> { });
			callbacks.runAll();
			assertTrue(staleEvents.isEmpty());
			assertTrue(cancelledEvents.isEmpty(), "Superseded cancellation callbacks are stale too");
			assertReleased(stale);
			assertReleased(cancelled);
			release.countDown();
			callbacks.runUntilReleased(next);
			assertEquals(SUCCEEDED, next.state());
			assertEquals(List.of(blocker, latest), reads);
		} finally {
			release.countDown();
		}
	}

	@Test
	void runningCancellationReachesPredicateAndSuppressesNonCooperativeLateResult() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch returned = new CountDownLatch(1);
		AtomicReference<BooleanSupplier> predicate = new AtomicReference<>();
		List<ScanService.Event<LogFileModel>> events = new ArrayList<>();
		LogFileModel result = new LogFileModel(Files.writeString(tempDir.resolve("late.log"), "late"));
		try (FileOpenService service = new FileOpenService(1, 1, callbacks, (path, cancelled) -> {
			predicate.set(cancelled);
			started.countDown();
			awaitIgnoringInterrupts(release);
			returned.countDown();
			return result;
		})) {
			var job = service.open("owner", tempDir, (current, event) -> events.add(event));
			await(started);
			assertFalse(predicate.get().getAsBoolean());
			service.cancel("owner");
			assertTrue(predicate.get().getAsBoolean());
			release.countDown();
			await(returned);
			// A subsequent read proves the late result has passed through the worker's completion path.
			var next = service.open("next", tempDir, (current, event) -> { });
			callbacks.runUntilReleased(next);
			assertEquals(List.of(CANCELLED), states(events));
			assertReleased(job);
		} finally {
			release.countDown();
		}
	}

	@Test
	void closeDuringReadReleasesReferencesAndDiscardsLateResult() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch returned = new CountDownLatch(1);
		AtomicReference<BooleanSupplier> predicate = new AtomicReference<>();
		LogFileModel result = new LogFileModel(Files.writeString(tempDir.resolve("close-late.log"), "late"));
		try (FileOpenService service = new FileOpenService(1, 1, callbacks, (path, cancelled) -> {
			predicate.set(cancelled);
			started.countDown();
			awaitIgnoringInterrupts(release);
			returned.countDown();
			return result;
		})) {
			var running = service.open("running", tempDir, (job, event) -> fail("Callback after close"));
			await(started);
			var queued = service.open("queued", tempDir, (job, event) -> fail("Queued callback after close"));
			service.close();
			assertTrue(predicate.get().getAsBoolean());
			assertReleased(running);
			assertReleased(queued);
			assertEquals(0, service.queuedJobs());
			release.countDown();
			await(returned);
			callbacks.runAll();
			assertEquals(CANCELLED, running.state());
			assertEquals(CANCELLED, queued.state());
		} finally {
			release.countDown();
		}
	}

	@Test
	void cancelOrCloseSuppressesHeldSuccessAndCloseReleasesReferencesImmediately() throws Exception {
		for (boolean close : List.of(false, true)) {
			ManualExecutor callbacks = new ManualExecutor();
			List<ScanService.Event<LogFileModel>> events = new ArrayList<>();
			LogFileModel result = new LogFileModel(Files.writeString(tempDir.resolve("held.log"), "held"));
			try (FileOpenService service = new FileOpenService(1, 1, callbacks, (path, cancelled) -> result)) {
				var job = service.open("owner", tempDir, (current, event) -> events.add(event));
				callbacks.awaitSize(3);
				assertEquals(SUCCEEDED, job.state());
				if (close) {
					service.close();
					assertReleased(job);
					var rejected = service.open("after-close", tempDir, (current, event) -> fail("Closed callback"));
					assertEquals(REJECTED, rejected.state());
					assertReleased(rejected);
				} else {
					service.cancel("owner");
				}
				callbacks.runAll();
				assertEquals(close ? List.of() : List.of(CANCELLED), states(events));
				assertReleased(job);
			}
		}
	}

	@Test
	void openerFailureIsDeliveredAndDoesNotDisableWorker() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		IOException failure = new IOException("read failure");
		AtomicInteger calls = new AtomicInteger();
		List<ScanService.Event<LogFileModel>> events = new ArrayList<>();
		try (FileOpenService service = new FileOpenService(1, 1, callbacks, (path, cancelled) -> {
			if (calls.incrementAndGet() == 1)
				throw failure;
			return null;
		})) {
			var failed = service.open("owner", tempDir, (job, event) -> events.add(event));
			callbacks.runUntilReleased(failed);
			assertEquals(List.of(QUEUED, RUNNING, FAILED), states(events));
			assertSame(failure, events.getLast().error());
			assertNull(events.getLast().result());
			var next = service.open("owner", tempDir, (job, event) -> { });
			callbacks.runUntilReleased(next);
			assertEquals(SUCCEEDED, next.state());
		}
	}

	@Test
	void openingDoesNotShareSaturatedScanWorkers() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ManualExecutor callbacks = new ManualExecutor();
		Path path = Files.writeString(tempDir.resolve("independent.log"), "independent");
		try (ScanService scans = new ScanService(1, 1, Runnable::run);
				FileOpenService opens = new FileOpenService(1, 1, callbacks)) {
			scans.submit("running", job -> {
				started.countDown();
				awaitIgnoringInterrupts(release);
				return null;
			}, (job, event) -> { });
			await(started);
			scans.submit("queued", job -> null, (job, event) -> { });
			var opened = opens.open("owner", path, (job, event) -> { });
			callbacks.runUntilReleased(opened);
			assertEquals(SUCCEEDED, opened.state());
			assertEquals(1, scans.activeJobs());
			assertEquals(1, scans.queuedJobs());
		} finally {
			release.countDown();
		}
	}

	private static List<ScanService.State> states(List<ScanService.Event<LogFileModel>> events) {
		return events.stream().map(ScanService.Event::state).toList();
	}

	private static void assertReleased(ScanService.Job<?> job) throws ReflectiveOperationException {
		assertFalse(job.isCurrent());
		for (String name : List.of("key", "work", "listener")) {
			var field = ScanService.Job.class.getDeclaredField(name);
			field.setAccessible(true);
			assertNull(field.get(job), name);
		}
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test coordination");
	}

	private static void awaitIgnoringInterrupts(CountDownLatch latch) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (true) {
			try {
				assertTrue(latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS),
						"Timed out waiting for release");
				return;
			} catch (InterruptedException e) {
				// Deliberately emulate a read which does not cooperate with cancellation.
			}
		}
	}

	private static final class ManualExecutor implements Executor {
		private final Queue<Runnable> tasks = new ArrayDeque<>();

		@Override
		public synchronized void execute(Runnable command) {
			tasks.add(command);
			notifyAll();
		}

		synchronized void awaitSize(int size) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (tasks.size() < size) {
				long remaining = deadline - System.nanoTime();
				assertTrue(remaining > 0, "Timed out waiting for callbacks");
				TimeUnit.NANOSECONDS.timedWait(this, remaining);
			}
		}

		void runAll() {
			Runnable task;
			while ((task = poll()) != null)
				task.run();
		}

		void runUntilReleased(ScanService.Job<?> job) throws InterruptedException {
			while (job.isCurrent()) {
				awaitSize(1);
				poll().run();
			}
			runAll();
		}

		private synchronized Runnable poll() {
			return tasks.poll();
		}
	}
}