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

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static ch.eitchnet.luminelog.service.ScanService.State.*;
import static org.junit.jupiter.api.Assertions.*;

class ScanServiceTest {

	@Test
	void deliversStatesOnCallbackExecutorAndReleasesTerminalKey() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		List<ScanService.Event<String>> events = new ArrayList<>();
		Thread callbackThread = Thread.currentThread();
		AtomicReference<Thread> workerThread = new AtomicReference<>();
		try (ScanService service = new ScanService(callbacks)) {
			ScanService.Job<String> job = service.submit("key", current -> {
				workerThread.set(Thread.currentThread());
				assertTrue(current.isCurrent());
				assertEquals(RUNNING, current.state());
				return "answer";
			}, (current, event) -> {
				assertSame(callbackThread, Thread.currentThread());
				assertTrue(current.isCurrent());
				events.add(event);
			});
			callbacks.awaitSize(3);
			assertTrue(events.isEmpty());
			assertEquals(SUCCEEDED, job.state());
			assertTrue(job.isCurrent());
			callbacks.runAll();
			assertEquals(List.of(QUEUED, RUNNING, SUCCEEDED), states(events));
			assertEquals("answer", events.getLast().result());
			assertNull(events.getLast().error());
			assertFalse(job.isCurrent());
			assertTrue(workerThread.get().isDaemon());
			assertTrue(workerThread.get().getName().startsWith("scan-worker-"));
			assertNotSame(callbackThread, workerThread.get());
			job.cancel();
			assertEquals(SUCCEEDED, job.state());
			assertEquals(2, ScanService.DEFAULT_WORKERS);
			assertEquals(8, ScanService.DEFAULT_QUEUE_CAPACITY);
		}
	}

	@Test
	void boundsWorkersAndQueueForOneHundredAndTwoHundredSubmissions() throws Exception {
		assertBounds(100);
		assertBounds(200);
	}

	private void assertBounds(int submissions) throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		CountDownLatch started = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(2);
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		List<ScanService.Job<Integer>> jobs = new ArrayList<>();
		ScanService.Work<Integer> work = job -> {
			calls.incrementAndGet();
			started.countDown();
			awaitIgnoringInterrupts(release, interrupted);
			return 1;
		};
		ScanService.Listener<Integer> listener = (job, event) -> {
			assertTrue(job.isCurrent());
			if (event.state() == REJECTED) {
				assertInstanceOf(RejectedExecutionException.class, event.error());
				rejected.incrementAndGet();
			}
		};
		try (ScanService service = new ScanService(2, 8, callbacks)) {
			jobs.add(service.submit(0, work, listener));
			jobs.add(service.submit(1, work, listener));
			await(started);
			for (int i = 2; i < submissions; i++) {
				jobs.add(service.submit(i, work, listener));
			}
			assertEquals(2, service.activeJobs());
			assertEquals(8, service.queuedJobs());
			assertEquals(submissions - 10, jobs.stream().filter(job -> job.state() == REJECTED).count());
			callbacks.runAll();
			assertEquals(submissions - 10, rejected.get());
			jobs.get(0).cancel();
			service.cancel(1);
			await(interrupted);
			callbacks.runAll();
			assertEquals(2, service.activeJobs());
			assertEquals(8, service.queuedJobs());
			assertEquals(2, calls.get());
			ScanService.Job<Integer> excess = service.submit("excess", work, listener);
			assertEquals(REJECTED, excess.state());
			callbacks.runAll();
			assertFalse(excess.isCurrent());
			assertEquals(submissions - 9, rejected.get());
		} finally {
			release.countDown();
		}
	}

	@Test
	void supersedingQueuedJobsPhysicallyReclaimsQueueSlots() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger obsoleteCalls = new AtomicInteger();
		AtomicInteger obsoleteEvents = new AtomicInteger();
		List<ScanService.Event<Integer>> latestEvents = new ArrayList<>();
		try (ScanService service = new ScanService(1, 1, callbacks)) {
			service.submit("blocker", job -> {
				started.countDown();
				awaitIgnoringInterrupts(release, new CountDownLatch(0));
				return 0;
			}, (job, event) -> { });
			await(started);
			ScanService.Job<Integer> previous = null;
			for (int i = 0; i < 100; i++) {
				ScanService.Job<Integer> next = service.submit("same", job -> obsoleteCalls.incrementAndGet(),
						(job, event) -> obsoleteEvents.incrementAndGet());
				if (previous != null) {
					assertTrue(previous.isCancelled());
					assertFalse(previous.isCurrent());
				}
				previous = next;
				assertEquals(QUEUED, next.state());
				assertEquals(1, service.queuedJobs());
			}
			ScanService.Job<Integer> latest = service.submit("same", job -> 42,
					(job, event) -> latestEvents.add(event));
			assertTrue(previous.isCancelled());
			callbacks.runAll();
			assertEquals(0, obsoleteEvents.get());
			release.countDown();
			callbacks.runUntilReleased(latest);
			assertEquals(0, obsoleteCalls.get());
			assertEquals(List.of(QUEUED, RUNNING, SUCCEEDED), states(latestEvents));
			assertEquals(42, latestEvents.getLast().result());
		} finally {
			release.countDown();
		}
	}

	@Test
	void suppressesAlreadyEnqueuedSuccessWhenSuperseded() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		AtomicInteger oldEvents = new AtomicInteger();
		List<ScanService.Event<Integer>> events = new ArrayList<>();
		try (ScanService service = new ScanService(1, 1, callbacks)) {
			ScanService.Job<Integer> old = service.submit("key", job -> 1,
					(job, event) -> oldEvents.incrementAndGet());
			callbacks.awaitSize(3);
			ScanService.Job<Integer> latest = service.submit("key", job -> 2,
					(job, event) -> events.add(event));
			callbacks.runUntilReleased(latest);
			assertTrue(old.isCancelled());
			assertFalse(old.isCurrent());
			assertEquals(0, oldEvents.get());
			assertEquals(2, events.getLast().result());
		}
	}

	@Test
	void cancelPendingSuccessInvalidatesEveryOldCallback() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		List<ScanService.Event<Integer>> events = new ArrayList<>();
		try (ScanService service = new ScanService(1, 1, callbacks)) {
			ScanService.Job<Integer> job = service.submit("key", current -> 1, (current, event) -> {
				assertTrue(current.isCurrent());
				events.add(event);
			});
			callbacks.awaitSize(3);
			service.cancel("key");
			job.cancel();
			assertTrue(job.isCancelled());
			callbacks.runAll();
			assertEquals(List.of(CANCELLED), states(events));
			assertFalse(job.isCurrent());
			assertNull(events.getFirst().result());
		}
	}

	@Test
	void cancellingQueuedJobMakesRoomWithoutRunningIt() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger cancelledCalls = new AtomicInteger();
		List<ScanService.Event<Integer>> events = new ArrayList<>();
		try (ScanService service = new ScanService(1, 1, callbacks)) {
			service.submit("blocker", job -> {
				started.countDown();
				awaitIgnoringInterrupts(release, new CountDownLatch(0));
				return 0;
			}, (job, event) -> { });
			await(started);
			ScanService.Job<Integer> cancelled = service.submit("queued", job -> cancelledCalls.incrementAndGet(),
					(job, event) -> events.add(event));
			cancelled.cancel();
			assertEquals(0, service.queuedJobs());
			ScanService.Job<Integer> next = service.submit("next", job -> 9, (job, event) -> { });
			assertEquals(QUEUED, next.state());
			callbacks.runAll();
			assertEquals(List.of(CANCELLED), states(events));
			release.countDown();
			callbacks.runUntilReleased(next);
			assertEquals(0, cancelledCalls.get());
		} finally {
			release.countDown();
		}
	}

	@Test
	void cancelsRunningWorkByInterruptAndSuppressesItsLateSuccess() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<ScanService.Event<Integer>> events = new ArrayList<>();
		try (ScanService service = new ScanService(1, 1, callbacks)) {
			ScanService.Job<Integer> old = service.submit("key", job -> {
				started.countDown();
				awaitIgnoringInterrupts(release, interrupted);
				return 1;
			}, (job, event) -> events.add(event));
			await(started);
			old.cancel();
			await(interrupted);
			callbacks.runAll();
			assertEquals(List.of(CANCELLED), states(events));
			ScanService.Job<Integer> next = service.submit("key", job -> 2, (job, event) -> { });
			assertEquals(QUEUED, next.state());
			release.countDown();
			callbacks.runUntilReleased(next);
			assertEquals(List.of(CANCELLED), states(events));
			assertEquals(SUCCEEDED, next.state());
		} finally {
			release.countDown();
		}
	}

	@Test
	void supersedingRunningWorkInterruptsAndSuppressesOldCallbacks() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger oldEvents = new AtomicInteger();
		try (ScanService service = new ScanService(1, 1, callbacks)) {
			ScanService.Job<Integer> old = service.submit("key", job -> {
				started.countDown();
				awaitIgnoringInterrupts(release, interrupted);
				return 1;
			}, (job, event) -> oldEvents.incrementAndGet());
			await(started);
			ScanService.Job<Integer> next = service.submit("key", job -> 2, (job, event) -> { });
			await(interrupted);
			assertTrue(old.isCancelled());
			assertEquals(QUEUED, next.state());
			assertEquals(1, service.activeJobs());
			callbacks.runAll();
			assertEquals(0, oldEvents.get());
			release.countDown();
			callbacks.runUntilReleased(next);
			assertEquals(0, oldEvents.get());
		} finally {
			release.countDown();
		}
	}

	@Test
	void closeReturnsWhileNoncooperativeWorkStillRunsAndCancelsQueuedJobs() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch exited = new CountDownLatch(1);
		AtomicInteger queuedCalls = new AtomicInteger();
		List<ScanService.Event<Integer>> runningEvents = new ArrayList<>();
		List<ScanService.Event<Integer>> queuedEvents = new ArrayList<>();
		ScanService service = new ScanService(1, 1, callbacks);
		try {
			ScanService.Job<Integer> running = service.submit("running", job -> {
				started.countDown();
				awaitIgnoringInterrupts(release, interrupted);
				exited.countDown();
				return 1;
			}, (job, event) -> runningEvents.add(event));
			await(started);
			ScanService.Job<Integer> queued = service.submit("queued", job -> queuedCalls.incrementAndGet(),
					(job, event) -> queuedEvents.add(event));
			service.close();
			service.close();
			await(interrupted);
			assertEquals(1, exited.getCount());
			assertEquals(1, service.activeJobs());
			assertEquals(0, service.queuedJobs());
			assertTrue(running.isCancelled());
			assertTrue(queued.isCancelled());
			assertReleased(running);
			assertReleased(queued);
			var current = ScanService.class.getDeclaredField("current");
			current.setAccessible(true);
			assertTrue(((java.util.Map<?, ?>) current.get(service)).isEmpty());
			callbacks.runAll();
			assertTrue(runningEvents.isEmpty());
			assertTrue(queuedEvents.isEmpty());
			ScanService.Job<Integer> rejected = service.submit("closed", job -> 3, (job, event) -> {
				fail("Shutdown must suppress callbacks");
			});
			assertEquals(REJECTED, rejected.state());
			assertReleased(rejected);
			callbacks.runAll();
			assertFalse(rejected.isCurrent());
			release.countDown();
			await(exited);
			assertEquals(0, queuedCalls.get());
		} finally {
			service.close();
			release.countDown();
		}
	}

	@Test
	void closeInvalidatesSuccessAlreadyWaitingForDelivery() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		List<ScanService.Event<Integer>> events = new ArrayList<>();
		ScanService service = new ScanService(callbacks);
		try {
			ScanService.Job<Integer> job = service.submit("key", current -> 1,
					(current, event) -> events.add(event));
			callbacks.awaitSize(3);
			service.close();
			assertTrue(job.isCancelled());
			assertReleased(job);
			callbacks.runAll();
			assertTrue(events.isEmpty());
			assertFalse(job.isCurrent());
		} finally {
			service.close();
		}
	}

	@Test
	void closeReleasesAlreadyCancelledJobWithoutDeliveringPendingCancellation() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		AtomicInteger events = new AtomicInteger();
		try (ScanService service = new ScanService(callbacks)) {
			ScanService.Job<Integer> job = service.submit("key", current -> 1,
					(current, event) -> events.incrementAndGet());
			callbacks.awaitSize(3);
			job.cancel();
			assertTrue(job.isCurrent());
			assertTrue(job.isCancelled());
			service.close();
			assertReleased(job);
			callbacks.runAll();
			assertEquals(0, events.get());
		}
	}

	@Test
	void workExceptionsBecomeFailedEventsAndWorkerRemainsUsable() throws Exception {
		ManualExecutor callbacks = new ManualExecutor();
		Exception failure = new Exception("broken scan");
		List<ScanService.Event<Integer>> events = new ArrayList<>();
		try (ScanService service = new ScanService(1, 1, callbacks)) {
			ScanService.Job<Integer> failed = service.submit("key", job -> { throw failure; },
					(job, event) -> events.add(event));
			callbacks.awaitSize(3);
			callbacks.runAll();
			assertEquals(FAILED, failed.state());
			assertEquals(List.of(QUEUED, RUNNING, FAILED), states(events));
			assertSame(failure, events.getLast().error());
			assertNull(events.getLast().result());
			assertFalse(failed.isCurrent());
			ScanService.Job<Integer> next = service.submit("key", job -> 2, (job, event) -> { });
			callbacks.runUntilReleased(next);
			assertEquals(SUCCEEDED, next.state());
		}
	}

	@Test
	void directCallbackCanCancelBeforeWorkIsScheduled() {
		AtomicBoolean called = new AtomicBoolean();
		List<ScanService.State> events = new ArrayList<>();
		try (ScanService service = new ScanService(Runnable::run)) {
			ScanService.Job<Integer> job = service.submit("key", current -> {
				called.set(true);
				return 1;
			}, (current, event) -> {
				assertTrue(current.isCurrent());
				events.add(event.state());
				if (event.state() == QUEUED) {
					current.cancel();
				}
			});
			assertEquals(List.of(QUEUED, CANCELLED), events);
			assertFalse(called.get());
			assertTrue(job.isCancelled());
			assertFalse(job.isCurrent());
			assertEquals(0, service.queuedJobs());
		}
	}

	@Test
	void validatesConfiguration() {
		assertThrows(IllegalArgumentException.class, () -> new ScanService(0, 1, Runnable::run));
		assertThrows(IllegalArgumentException.class, () -> new ScanService(1, 0, Runnable::run));
		assertThrows(NullPointerException.class, () -> new ScanService(null));
	}

	private static void assertReleased(ScanService.Job<?> job) throws ReflectiveOperationException {
		assertFalse(job.isCurrent());
		for (String name : List.of("key", "work", "listener")) {
			var field = ScanService.Job.class.getDeclaredField(name);
			field.setAccessible(true);
			assertNull(field.get(job), name);
		}
	}

	private static List<ScanService.State> states(List<? extends ScanService.Event<?>> events) {
		return events.stream().map(ScanService.Event::state).toList();
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test coordination");
	}

	private static void awaitIgnoringInterrupts(CountDownLatch latch, CountDownLatch interrupted) {
		boolean done = false;
		while (!done) {
			try {
				latch.await();
				done = true;
			} catch (InterruptedException e) {
				interrupted.countDown();
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
			while ((task = poll()) != null) {
				task.run();
			}
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