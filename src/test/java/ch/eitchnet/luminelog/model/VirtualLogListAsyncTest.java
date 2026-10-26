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
package ch.eitchnet.luminelog.model;

import ch.eitchnet.luminelog.service.LineLoadingService;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VirtualLogListAsyncTest {

	@TempDir
	Path tempDir;

	private final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
	private final LineLoadingService loader = new LineLoadingService(callbacks::add);
	private final List<VirtualLogList> lists = new ArrayList<>();

	@AfterEach
	void closeListsAndLoader() {
		lists.forEach(VirtualLogList::close);
		loader.close();
	}

	private VirtualLogList createList(LogFileModel model) {
		VirtualLogList list = new VirtualLogList(model, loader);
		lists.add(list);
		return list;
	}

	private Runnable awaitCallback() throws Exception {
		Runnable callback = callbacks.poll(5, TimeUnit.SECONDS);
		assertNotNull(callback, "Worker did not deliver a completion");
		return callback;
	}

	private LogLine loaded(VirtualLogList list, int index) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		LogLine line = list.get(index);
		while (line.isLoading()) {
			Runnable callback = callbacks.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			assertNotNull(callback, "Timed out loading row " + index);
			callback.run();
			line = list.get(index);
		}
		return line;
	}

	@Test
	void prefetchDoesNotEvictDemandLoadedRows() throws Exception {
		Path file = tempDir.resolve("prefetch-selection.log");
		Files.writeString(file, "line\n".repeat(3000));
		var list = createList(new LogFileModel(file));
		assertEquals("line", loaded(list, 0).content());
		awaitCallback().run();
		for (int row = 0; row < 20; row++)
			assertTrue(list.get(row).isAvailable(), "Prefetch must not displace the visible batch");
	}

	@Test
	void demandPromotesPendingPrefetch() throws Exception {
		Path file = tempDir.resolve("prefetch-demand.log");
		Files.writeString(file, "line\n".repeat(3000));
		var list = createList(new LogFileModel(file));
		assertEquals("line", loaded(list, 0).content());
		Runnable prefetch = awaitCallback();
		assertTrue(list.get(1010).isLoading());
		prefetch.run();
		assertEquals("line", list.get(1010).content(), "Visible demand must promote speculative work");
	}

	@Test
	void nonLoadingScopeIsNestedAndRestoredAfterFailure() throws Exception {
		Path file = tempDir.resolve("selection-scope.log");
		Files.writeString(file, "line\n".repeat(3000));
		var list = createList(new LogFileModel(file));
		list.withoutLoading(() -> {
			list.withoutLoading(() -> assertTrue(list.get(0).isLoading()));
			assertTrue(list.get(2000).isLoading());
		});
		assertThrows(IllegalStateException.class, () -> list.withoutLoading(() -> {
			throw new IllegalStateException("selection listener failed");
		}));
		assertEquals(0, loader.activeJobs() + loader.queuedJobs());
		assertTrue(callbacks.isEmpty());
		assertEquals("line", loaded(list, 0).content(), "Normal reads must resume outside the scope");
	}

	@Test
	void sparseRequestsBoundDeferredWorkAndReleaseItOnClose() throws Exception {
		Path file = tempDir.resolve("sparse-queue.log");
		Files.writeString(file, "line\n".repeat(20000));
		var model = new LogFileModel(file);
		var list = new VirtualLogList(model, model.snapshot(), 4096, 4, loader, _ -> true);
		lists.add(list);
		for (int index = 0; index < 20000; index += 1000)
			assertTrue(list.get(index).isLoading());
		var pendingField = VirtualLogList.class.getDeclaredField("pending");
		pendingField.setAccessible(true);
		var deferredField = VirtualLogList.class.getDeclaredField("deferred");
		deferredField.setAccessible(true);
		assertEquals(VirtualLogList.MAX_PENDING_READS, ((java.util.Map<?, ?>) pendingField.get(list)).size());
		assertEquals(4, ((java.util.Set<?>) deferredField.get(list)).size());
		Runnable completion = awaitCallback();
		list.close();
		completion.run();
		assertTrue(((java.util.Map<?, ?>) pendingField.get(list)).isEmpty());
		assertTrue(((java.util.Set<?>) deferredField.get(list)).isEmpty());
		assertEquals(new VirtualLogList.CacheUsage(0, 0, 0), list.cacheUsage());
	}

	@Test
	void sparseReadsRetainOnlyRequestedRowsWithinCacheBudget() throws Exception {
		Path file = tempDir.resolve("sparse-cache.log");
		Files.writeString(file, "match\n".repeat(20000));
		var model = new LogFileModel(file);
		var list = new VirtualLogList(model, model.snapshot(), 4096, 4, loader, _ -> true);
		lists.add(list);
		for (int index = 0; index < 20000; index += 2000) {
			assertEquals("match", loaded(list, index).content());
			assertEquals(0, list.cacheUsage().batchBytes());
			assertTrue(list.cacheUsage().totalBytes() <= 4096);
			assertTrue(list.cacheUsage().entries() <= 4);
		}
		assertEquals(4, list.cacheUsage().entries());
		assertEquals("match", list.get(18000).content());
	}

	@Test
	void completionPublishesOnlyWhenOwnerDrainsCallback() throws Exception {
		Path file = tempDir.resolve("held.log");
		Files.writeString(file, "first\nsecond");
		VirtualLogList list = createList(new LogFileModel(file));
		Thread owner = Thread.currentThread();
		AtomicInteger updates = new AtomicInteger();
		list.addListener((ListChangeListener<LogLine>) change -> {
			assertSame(owner, Thread.currentThread());
			while (change.next()) {
				assertTrue(change.wasUpdated());
				if (change.getFrom() <= 0 && change.getTo() > 0)
					updates.incrementAndGet();
			}
		});

		LogLine initial = list.get(0);
		assertTrue(initial.isLoading());
		assertFalse(initial.isAvailable());
		assertFalse(initial.isReadError());
		assertNull(initial.content());
		assertEquals(1, initial.lineNumber());
		Runnable completion = awaitCallback();
		assertTrue(list.get(0).isLoading());
		assertEquals(0, updates.get());
		assertEquals(new VirtualLogList.CacheUsage(0, 0, 0), list.cacheUsage());

		completion.run();
		LogLine available = list.get(0);
		assertEquals("first", available.content());
		assertTrue(available.isAvailable());
		assertFalse(available.isLoading());
		assertSame(available, list.get(0));
		assertEquals(1, updates.get());
	}

	@Test
	void missingFileStillReturnsLoadingBeforeWorkerFailureIsPublished() throws Exception {
		Path file = tempDir.resolve("deleted.log");
		Files.writeString(file, "indexed");
		LogFileModel model = new LogFileModel(file);
		Files.delete(file);
		VirtualLogList list = createList(model);

		LogLine initial = list.get(0);
		assertTrue(initial.isLoading());
		assertNull(initial.content());
		Runnable completion = awaitCallback();
		assertTrue(list.get(0).isLoading());
		completion.run();
		LogLine error = list.get(0);
		assertTrue(error.isReadError());
		assertFalse(error.isAvailable());
		assertFalse(error.isLoading());
	}

	@Test
	void rapidViewportJumpsKeepPerListRequestsBounded() throws Exception {
		Path file = tempDir.resolve("jumps.log");
		Files.writeString(file, "row\n".repeat(10_000));
		VirtualLogList list = createList(new LogFileModel(file));
		var field = VirtualLogList.class.getDeclaredField("pending");
		field.setAccessible(true);
		for (int i = 0; i < 100; i++) {
			assertTrue(list.get(i * 97).isLoading());
			assertTrue(((java.util.Map<?, ?>) field.get(list)).size() <= VirtualLogList.MAX_PENDING_READS);
			assertTrue(loader.queuedJobs() <= VirtualLogList.MAX_PENDING_READS);
		}
		assertEquals("row", loaded(list, 99 * 97).content());
	}

	@Test
	void failedRowsCanBeRetriedWithoutTreatingErrorsAsText() throws Exception {
		Path file = tempDir.resolve("retry.log");
		Files.writeString(file, "readable");
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model);
		Path temporarilyMoved = tempDir.resolve("temporarily-moved.log");
		Files.move(file, temporarilyMoved);
		assertTrue(loaded(list, 0).isReadError());
		Files.move(temporarilyMoved, file);
		list.retryUnavailable();
		assertTrue(list.get(0).isLoading());
		assertEquals("readable", loaded(list, 0).content());
	}

	@Test
	void appendRejectsQueuedCompletionForPreviousFinalLine() throws Exception {
		assertStaleCompletionRejected(true);
	}

	@Test
	void repeatedAppendsKeepCompletedStablePrefixButNeverOldFinalLine() throws Exception {
		Path file = tempDir.resolve("stable-prefix.log");
		Files.writeString(file, "one\ntwo\ntail");
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model);
		assertTrue(list.get(0).isLoading());
		Runnable oldCompletion = awaitCallback();
		for (int i = 0; i < 4; i++) {
			Files.writeString(file, " suffix", StandardOpenOption.APPEND);
			model.updateIndex();
			list.publish(model.snapshot());
		}
		oldCompletion.run();
		assertEquals("one", list.get(0).content());
		assertEquals("two", list.get(1).content());
		assertTrue(list.get(2).isLoading());
		assertEquals("tail" + " suffix".repeat(4), loaded(list, 2).content());
	}

	@Test
	void rewriteRejectsQueuedCompletionForPreviousContent() throws Exception {
		assertStaleCompletionRejected(false);
	}

	private void assertStaleCompletionRejected(boolean append) throws Exception {
		Path file = tempDir.resolve("stale.log");
		Files.writeString(file, "prefix");
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model);
		assertTrue(list.get(0).isLoading());
		Runnable staleCompletion = awaitCallback();
		if (append)
			Files.writeString(file, " suffix", StandardOpenOption.APPEND);
		else
			Files.writeString(file, "new");
		model.updateIndex();
		list.publish(model.snapshot());
		AtomicInteger updates = new AtomicInteger();
		list.addListener((ListChangeListener<LogLine>) _ -> updates.incrementAndGet());

		staleCompletion.run();
		assertEquals(0, updates.get());
		assertEquals(new VirtualLogList.CacheUsage(0, 0, 0), list.cacheUsage());
		assertTrue(list.get(0).isLoading());
		assertEquals(append ? "prefix suffix" : "new", loaded(list, 0).content());
		assertTrue(updates.get() > 0);
	}

	@Test
	void closeDropsQueuedCompletionWithoutCachingOrNotification() throws Exception {
		Path file = tempDir.resolve("closed.log");
		Files.writeString(file, "first\nsecond");
		VirtualLogList list = createList(new LogFileModel(file));
		AtomicInteger notifications = new AtomicInteger();
		list.addListener((ListChangeListener<LogLine>) _ -> notifications.incrementAndGet());
		assertTrue(list.get(0).isLoading());
		Runnable completion = awaitCallback();

		list.close();
		completion.run();
		while ((completion = callbacks.poll()) != null)
			completion.run();
		assertEquals(0, notifications.get());
		assertEquals(new VirtualLogList.CacheUsage(0, 0, 0), list.cacheUsage());
	}

	@Test
	void removedUncachedRowsAreExplicitHistoricalErrorsWithoutLoading() throws Exception {
		Path file = tempDir.resolve("historical.log");
		Files.writeString(file, "one\ntwo\nthree");
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model);
		AtomicInteger replacements = new AtomicInteger();
		list.addListener((ListChangeListener<LogLine>) change -> {
			while (change.next()) {
				assertTrue(change.wasReplaced());
				assertEquals(3, change.getRemovedSize());
				for (int i = 0; i < change.getRemovedSize(); i++) {
					LogLine removed = change.getRemoved().get(i);
					assertEquals(i + 1, removed.lineNumber());
					assertTrue(removed.isReadError());
					assertFalse(removed.isAvailable());
					assertFalse(removed.isLoading());
					assertTrue(removed.errorMessage().toLowerCase(java.util.Locale.ROOT).contains("historical"));
				}
				replacements.incrementAndGet();
			}
		});
		Files.writeString(file, "new");
		model.updateIndex();
		Files.delete(file);
		list.publish(model.snapshot());
		assertEquals(1, replacements.get());
		assertTrue(callbacks.isEmpty());
		assertEquals(new VirtualLogList.CacheUsage(0, 0, 0), list.cacheUsage());
	}

	@Test
	void getPublishAndCloseRejectNonOwnerThread() throws Exception {
		Path file = tempDir.resolve("owner.log");
		Files.writeString(file, "one");
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model);
		FutureTask<Void> checks = new FutureTask<>(() -> {
			assertThrows(IllegalStateException.class, () -> list.get(0));
			assertThrows(IllegalStateException.class, () -> list.publish(model.snapshot()));
			assertThrows(IllegalStateException.class, list::close);
			return null;
		});
		Thread.ofPlatform().start(checks);
		checks.get(5, TimeUnit.SECONDS);
		assertTrue(callbacks.isEmpty());
		assertEquals("one", loaded(list, 0).content());
	}
}