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

import ch.eitchnet.luminelog.model.ChunkedOccurrenceIndex;
import ch.eitchnet.luminelog.model.OccurrenceIndex;
import ch.eitchnet.luminelog.model.SpillableOccurrenceIndex;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class OccurrencePageServiceTest {

	@Test
	void workerAndPhysicalQueueBoundsRejectWithoutCallerReads() throws Exception {
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		AtomicInteger deliveries = new AtomicInteger();
		Thread caller = Thread.currentThread();
		HeldExecutor callbacks = new HeldExecutor();
		try (OccurrenceIndex index = index(1);
			 OccurrencePageService service = new OccurrencePageService(2, 3, callbacks, (source, page) -> {
				assertNotSame(caller, Thread.currentThread());
				reads.incrementAndGet();
				entered.countDown();
				awaitUninterruptibly(release);
				return new int[]{page};
			})) {
			try {
				assertTrue(service.request(this, index, 0, result -> deliveries.incrementAndGet()));
				assertTrue(service.request(this, index, 1, result -> deliveries.incrementAndGet()));
				assertTrue(entered.await(5, TimeUnit.SECONDS));
				for (int page = 2; page < 5; page++)
					assertTrue(service.request(this, index, page, result -> deliveries.incrementAndGet()));
				assertEquals(2, service.activeJobs());
				assertEquals(3, service.queuedJobs());
				assertEquals(5, service.admittedJobs());
				assertFalse(service.request(this, index, 5, result -> deliveries.incrementAndGet()));
				assertEquals(2, reads.get());
				release.countDown();
				await(() -> reads.get() == 5 && service.activeJobs() == 0 && service.queuedJobs() == 0);
				assertEquals(5, service.admittedJobs());
				assertFalse(service.request(this, index, 6, result -> deliveries.incrementAndGet()));
				await(() -> callbacks.tasks.size() == 1);
				callbacks.runOne();
				assertEquals(5, deliveries.get());
				assertEquals(0, service.admittedJobs());
			} finally {
				release.countDown();
			}
		}
	}

	@Test
	void deduplicatesByOwnerAndIndexIdentityUntilDelivery() throws Exception {
		HeldExecutor callbacks = new HeldExecutor();
		Object owner = new String("equal");
		Object otherOwner = new String("equal");
		AtomicInteger deliveries = new AtomicInteger();
		try (OccurrenceIndex index = index(1); OccurrenceIndex otherIndex = index(1);
			 OccurrencePageService service = new OccurrencePageService(1, 4, callbacks, (source, page) -> new int[]{page})) {
			assertTrue(service.request(owner, index, 0, result -> deliveries.incrementAndGet()));
			await(() -> callbacks.tasks.size() == 1);
			assertFalse(service.request(owner, index, 0, result -> fail("Duplicate callback")));
			assertTrue(service.request(otherOwner, index, 0, result -> deliveries.incrementAndGet()));
			assertTrue(service.request(owner, otherIndex, 0, result -> deliveries.incrementAndGet()));
			assertTrue(service.request(owner, index, 1, result -> deliveries.incrementAndGet()));
			await(() -> service.activeJobs() == 0 && service.queuedJobs() == 0);
			assertEquals(1, callbacks.tasks.size());
			callbacks.runOne();
			assertEquals(4, deliveries.get());
			assertTrue(service.request(owner, index, 0, result -> deliveries.incrementAndGet()));
			await(() -> callbacks.tasks.size() == 1);
			callbacks.runOne();
			assertEquals(5, deliveries.get());
		}
	}

	@Test
	void cancelledRunningReadKeepsAdmissionUntilExitAndSuppressesLateSuccess() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Object owner = new Object();
		HeldExecutor callbacks = new HeldExecutor();
		try (OccurrenceIndex index = index(1);
			 OccurrencePageService service = new OccurrencePageService(1, 1, callbacks, (source, page) -> {
				entered.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					interrupted.countDown();
					awaitUninterruptibly(release);
				}
				return new int[]{page};
			})) {
			try {
				assertTrue(service.request(owner, index, 0, result -> fail("Cancelled running result")));
				assertTrue(entered.await(5, TimeUnit.SECONDS));
				assertTrue(service.request(owner, index, 1, result -> fail("Cancelled queued result")));
				service.cancel(owner);
				assertTrue(interrupted.await(5, TimeUnit.SECONDS));
				assertEquals(1, service.activeJobs());
				assertEquals(0, service.queuedJobs());
				assertEquals(1, service.admittedJobs());
				assertJobReferencesCleared(service);
				assertTrue(service.request(new Object(), index, 2, result -> {}));
				assertFalse(service.request(new Object(), index, 3, result -> fail("Overload callback")));
				release.countDown();
				await(() -> callbacks.tasks.size() == 1 && service.activeJobs() == 0);
				callbacks.runOne();
				assertEquals(0, service.admittedJobs());
			} finally {
				release.countDown();
			}
		}
	}

	@Test
	void repeatedReadyCancellationKeepsOnlyOneHeldDispatcherAndNoJobReferences() throws Exception {
		HeldExecutor callbacks = new HeldExecutor();
		AtomicInteger reads = new AtomicInteger();
		Object owner = new Object();
		try (OccurrenceIndex index = index(1);
			 OccurrencePageService service = new OccurrencePageService(1, 1, callbacks, (source, page) -> {
				reads.incrementAndGet();
				return new int[]{page};
			})) {
			for (int page = 0; page < 500; page++) {
				int expected = page + 1;
				assertTrue(service.request(owner, index, page, result -> fail("Cancelled ready result")));
				await(() -> reads.get() == expected && service.activeJobs() == 0 && service.queuedJobs() == 0);
				service.cancel(owner);
				assertEquals(0, service.admittedJobs());
				assertTrue(callbacks.tasks.size() <= 1);
				assertTrue(((Collection<?>) field(service, "ready")).isEmpty());
			}
			assertEquals(1, callbacks.submissions.get());
			AtomicInteger delivered = new AtomicInteger();
			assertTrue(service.request(owner, index, 501, result -> delivered.incrementAndGet()));
			await(() -> reads.get() == 501 && service.activeJobs() == 0 && service.queuedJobs() == 0);
			callbacks.runOne();
			assertEquals(1, delivered.get());
			assertEquals(0, service.admittedJobs());
		}
	}

	@Test
	void closeIsImmediateClearsQueuedAndReadyAndSuppressesLateReads() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		HeldExecutor callbacks = new HeldExecutor();
		try (OccurrenceIndex index = index(1);
			 OccurrencePageService service = new OccurrencePageService(1, 2, callbacks, (source, page) -> {
				if (page == 1) {
					entered.countDown();
					awaitUninterruptibly(release);
				}
				return new int[]{page};
			})) {
			try {
				assertTrue(service.request(this, index, 0, result -> fail("Closed ready result")));
				await(() -> callbacks.tasks.size() == 1);
				assertTrue(service.request(this, index, 1, result -> fail("Closed running result")));
				assertTrue(entered.await(5, TimeUnit.SECONDS));
				assertTrue(service.request(this, index, 2, result -> fail("Closed queued result")));
				assertTimeoutPreemptively(Duration.ofSeconds(2), service::close);
				assertEquals(1, service.admittedJobs());
				assertEquals(0, service.queuedJobs());
				assertJobReferencesCleared(service);
				assertFalse(service.request(this, index, 3, result -> fail("Closed request")));
				callbacks.runOne();
				release.countDown();
				await(() -> service.admittedJobs() == 0);
				assertTrue(callbacks.tasks.isEmpty());
				service.close();
			} finally {
				release.countDown();
			}
		}
	}

	@Test
	void deliversIOExceptionAndSurvivesThrowingConsumer() throws Exception {
		IOException failure = new IOException("read failed");
		HeldExecutor callbacks = new HeldExecutor();
		AtomicReference<OccurrencePageService.Result> delivered = new AtomicReference<>();
		try (OccurrenceIndex index = index(1);
			 OccurrencePageService service = new OccurrencePageService(1, 2, callbacks, (source, page) -> {
				throw failure;
			})) {
			assertTrue(service.request(this, index, 0, result -> { throw new IllegalStateException("consumer"); }));
			assertTrue(service.request(this, index, 1, delivered::set));
			await(() -> service.activeJobs() == 0 && service.queuedJobs() == 0 && callbacks.tasks.size() == 1);
			callbacks.runOne();
			assertSame(failure, delivered.get().error());
			assertNull(delivered.get().identifiers());
			assertEquals(0, service.admittedJobs());
		}
	}

	@Test
	void readsFullPartialAndOutOfRangeMemoryPagesWithDirectCallbacks() throws Exception {
		assertEquals(SpillableOccurrenceIndex.PAGE_SIZE, OccurrencePageService.PAGE_SIZE);
		BlockingQueue<OccurrencePageService.Result> results = new LinkedBlockingQueue<>();
		try (OccurrenceIndex index = index(4099);
			 OccurrencePageService service = new OccurrencePageService(Runnable::run)) {
			for (int page : new int[]{0, 1, 2, Integer.MAX_VALUE}) {
				assertTrue(service.request(this, index, page, results::add));
				OccurrencePageService.Result result = results.poll(5, TimeUnit.SECONDS);
				assertNotNull(result);
				assertNull(result.error());
				int length = page == 0 ? 4096 : page == 1 ? 3 : 0;
				assertEquals(length, result.identifiers().length);
				for (int i = 0; i < length; i++)
					assertEquals((page * 4096 + i) * 2, result.identifiers()[i]);
			}
			await(() -> service.admittedJobs() == 0);
		}
	}

	@Test
	void directCallbacksCanReenterRequestCancelAndClose() throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		try (OccurrenceIndex index = index(1);
			 OccurrencePageService service = new OccurrencePageService(1, 2, Runnable::run)) {
			assertTrue(service.request(this, index, 0, result -> {
				service.cancel(this);
				assertTrue(service.request(this, index, 1, next -> {
					service.close();
					done.countDown();
				}));
			}));
			assertTrue(done.await(5, TimeUnit.SECONDS));
			await(() -> service.admittedJobs() == 0);
		}
	}

	@Test
	void rejectingCallbackExecutorDoesNotLeakAdmission() throws Exception {
		try (OccurrenceIndex index = index(1);
			 OccurrencePageService service = new OccurrencePageService(1, 1, runnable -> {
				throw new java.util.concurrent.RejectedExecutionException();
			})) {
			assertTrue(service.request(this, index, 0, result -> fail("Rejected delivery")));
			await(() -> service.admittedJobs() == 0);
			assertTrue(service.request(this, index, 1, result -> fail("Rejected delivery")));
			await(() -> service.admittedJobs() == 0);
		}
	}

	@Test
	void validatesConfigurationAndPageNumber() {
		assertThrows(IllegalArgumentException.class, () -> new OccurrencePageService(0, 1, Runnable::run));
		assertThrows(IllegalArgumentException.class, () -> new OccurrencePageService(1, -1, Runnable::run));
		try (OccurrenceIndex index = index(0);
			 OccurrencePageService service = new OccurrencePageService(1, 0, Runnable::run)) {
			assertThrows(IllegalArgumentException.class, () -> service.request(this, index, -1, result -> {}));
			assertEquals(0, service.admittedJobs());
		}
	}

	private static OccurrenceIndex index(int size) {
		ChunkedOccurrenceIndex.Builder builder = new ChunkedOccurrenceIndex.Builder();
		for (int i = 0; i < size; i++)
			builder.add(i * 2);
		return builder.build();
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, "Timed out waiting for loader state");
			Thread.sleep(1);
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try {
				assertTrue(latch.await(10, TimeUnit.SECONDS), "Reader was not released");
				break;
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static void assertJobReferencesCleared(OccurrencePageService service) throws Exception {
		for (Object job : (Collection<?>) field(service, "jobs"))
			for (String name : new String[]{"owner", "index", "completion", "result"})
				assertNull(field(job, name), name);
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static final class HeldExecutor implements Executor {
		private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
		private final AtomicInteger submissions = new AtomicInteger();

		@Override
		public void execute(Runnable command) {
			submissions.incrementAndGet();
			tasks.add(command);
		}

		private void runOne() throws InterruptedException {
			Runnable task = tasks.poll(5, TimeUnit.SECONDS);
			assertNotNull(task);
			task.run();
		}
	}
}