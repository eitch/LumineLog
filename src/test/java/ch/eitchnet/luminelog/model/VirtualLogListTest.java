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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VirtualLogListTest {

	@TempDir
	Path tempDir;

	final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
	final LineLoadingService loader = new LineLoadingService(callbacks::add);
	private final List<VirtualLogList> lists = new ArrayList<>();

	@AfterEach
	void closeListsAndLoader() {
		lists.forEach(VirtualLogList::close);
		loader.close();
	}

	VirtualLogList createList(LogFileModel model) {
		VirtualLogList list = new VirtualLogList(model, loader);
		lists.add(list);
		return list;
	}

	VirtualLogList createList(LogFileModel model, long byteBudget, int entryLimit) {
		VirtualLogList list = new VirtualLogList(model, byteBudget, entryLimit, loader);
		lists.add(list);
		return list;
	}

	LogLine loaded(VirtualLogList list, int index) throws Exception {
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
	public void longLinesMustNotFillAnEntryOnlyCache() throws Exception {
		Path file = tempDir.resolve("weighted.log");
		try (var writer = Files.newBufferedWriter(file)) {
			String line = "x".repeat(512 * 1024 - 1) + "\n";
			for (int i = 0; i < 24; i++)
				writer.write(line);
		}
		VirtualLogList list = createList(new LogFileModel(file));
		for (int i = 0; i < 24; i++) {
			assertEquals(512 * 1024 - 1, loaded(list, i).content().length());
			assertTrue(list.cacheUsage().totalBytes() <= VirtualLogList.DEFAULT_CACHE_BYTES);
			assertAccounting(list);
		}
		var field = VirtualLogList.class.getDeclaredField("cache");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		var cache = (java.util.Map<Integer, LogLine>) field.get(list);
		long decodedBytes = cache.values().stream().mapToLong(line -> 2L * line.content().length()).sum();
		assertTrue(decodedBytes <= 8 * 1024 * 1024, "Retained decoded character weight: " + decodedBytes);
	}

	@Test
	public void mixedUnicodeLinesRespectCombinedBudgetDuringRandomReads() throws Exception {
		Path file = tempDir.resolve("mixed.log");
		var expected = new java.util.ArrayList<String>();
		for (int i = 0; i < 80; i++)
			expected.add(i % 2 == 0 ? "😀é".repeat(1000) : "short-" + i);
		Files.writeString(file, String.join("\r\n", expected));
		VirtualLogList list = createList(new LogFileModel(file), 32 * 1024, 7);
		var random = new java.util.Random(42);
		for (int i = 0; i < 200; i++) {
			int index = random.nextInt(expected.size());
			assertEquals(expected.get(index), loaded(list, index).content());
			assertTrue(list.cacheUsage().totalBytes() <= 32 * 1024);
			assertTrue(list.cacheUsage().entries() <= 7);
			assertAccounting(list);
		}
	}

	@Test
	public void byteEvictionUsesAccessOrderAndKeepsRequestedRow() throws Exception {
		Path file = tempDir.resolve("lru.log");
		Files.writeString(file, "one\ntwo\nend");
		LogFileModel model = new LogFileModel(file);
		long batchWeight = VirtualLogList.batchWeight(model.snapshot().readBatch(0));
		long rowWeight = VirtualLogList.lineWeight(new LogLine(1, "one"));
		VirtualLogList list = createList(model, batchWeight + 2 * rowWeight, 100);
		LogLine first = loaded(list, 0);
		LogLine last = loaded(list, 2);
		assertSame(first, list.get(0));
		loaded(list, 1);
		assertSame(first, list.get(0));
		assertNotSame(last, loaded(list, 2));
		assertEquals(2, list.cacheUsage().entries());
		assertEquals(batchWeight + 2 * rowWeight, list.cacheUsage().totalBytes());
		assertAccounting(list);
	}

	@Test
	public void defaultEntryCeilingStillBoundsTinyLines() throws Exception {
		assertEquals(8 * 1024 * 1024, VirtualLogList.DEFAULT_CACHE_BYTES);
		assertEquals(1000, VirtualLogList.DEFAULT_CACHE_ENTRIES);
		Path file = tempDir.resolve("tiny.log");
		Files.writeString(file, "x\n".repeat(2500));
		VirtualLogList list = createList(new LogFileModel(file));
		for (int index : new int[] { 0, 1000, 2000, 0 }) {
			assertEquals("x", loaded(list, index).content());
			assertEquals(1000, list.cacheUsage().entries());
			assertTrue(list.cacheUsage().totalBytes() <= VirtualLogList.DEFAULT_CACHE_BYTES);
			assertAccounting(list);
		}
	}

	@Test
	public void batchAloneCanUseBudgetWithoutCachingRequestedRow() throws Exception {
		Path file = tempDir.resolve("batch-only.log");
		Files.writeString(file, "one\ntwo");
		LogFileModel model = new LogFileModel(file);
		long weight = VirtualLogList.batchWeight(model.snapshot().readBatch(0));
		VirtualLogList list = createList(model, weight, 10);
		LogLine first = loaded(list, 0);
		assertTrue(first.isReadError());
		assertFalse(first.isAvailable());
		assertTrue(list.cacheUsage().totalBytes() <= weight);
		assertAccounting(list);
		Files.delete(file);
		assertTrue(loaded(list, 1).isReadError());
		assertTrue(list.cacheUsage().totalBytes() <= weight);
		assertAccounting(list);
	}

	@Test
	public void overBudgetEntriesAndBatchesAreReturnedWithoutRetention() throws Exception {
		Path file = tempDir.resolve("uncached.log");
		String text = "é".repeat(2048);
		Files.writeString(file, text);
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model, 512, 10);
		LogLine first = loaded(list, 0);
		assertTrue(first.isReadError());
		assertFalse(first.isAvailable());
		assertSame(first, list.get(0));
		assertTrue(list.cacheUsage().totalBytes() <= 512);
		VirtualLogList disabled = createList(model, 0, 1);
		LogLine unavailable = loaded(disabled, 0);
		assertTrue(unavailable.isReadError());
		assertFalse(unavailable.isAvailable());
		assertEquals(0, disabled.cacheUsage().totalBytes());
		assertThrows(IllegalArgumentException.class, () -> new VirtualLogList(model, -1, 1, loader));
		assertThrows(IllegalArgumentException.class, () -> new VirtualLogList(model, 512, 0, loader));
		assertAccounting(list);
		assertAccounting(disabled);
	}

	@Test
	public void publicationReleasesBatchAndSubtractsInvalidatedWeights() throws Exception {
		Path file = tempDir.resolve("accounting-publication.log");
		Files.writeString(file, "one\ntwo");
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model);
		LogLine first = loaded(list, 0);
		Files.writeString(file, " suffix", java.nio.file.StandardOpenOption.APPEND);
		model.updateIndex();
		list.publish(model.snapshot());
		assertEquals(new VirtualLogList.CacheUsage(VirtualLogList.lineWeight(first), 0, 1), list.cacheUsage());
		assertSame(first, list.get(0));
		assertEquals("two suffix", loaded(list, 1).content());
		assertAccounting(list);
		Files.writeString(file, "replacement");
		model.updateIndex();
		list.publish(model.snapshot());
		assertEquals(new VirtualLogList.CacheUsage(0, 0, 0), list.cacheUsage());
		assertEquals("replacement", loaded(list, 0).content());
		assertAccounting(list);
	}

	@Test
	public void weightsIncludeErrorsAndUseUtf16EvenForCompactStrings() throws Exception {
		assertEquals(264, VirtualLogList.lineWeight(new LogLine(1, "abc")));
		assertEquals(272, VirtualLogList.lineWeight(new LogLine(1, "😀".repeat(3))));
		assertEquals(336, VirtualLogList.lineWeight(LogLine.readError(1, "error")));
		Path file = tempDir.resolve("error-weight.log");
		try (var output = new java.io.RandomAccessFile(file.toFile(), "rw")) {
			output.setLength(2 * LogFileModel.MAX_ENCODED_LINE_BYTES);
		}
		VirtualLogList list = createList(new LogFileModel(file), 4096, 10);
		LogLine error = loaded(list, 0);
		assertTrue(error.isReadError());
		assertSame(error, list.get(0));
		assertEquals(new VirtualLogList.CacheUsage(VirtualLogList.lineWeight(error), 0, 1), list.cacheUsage());
		assertAccounting(list);
	}

	private static void assertAccounting(VirtualLogList list) throws Exception {
		var cacheField = VirtualLogList.class.getDeclaredField("cache");
		cacheField.setAccessible(true);
		@SuppressWarnings("unchecked")
		var cache = (java.util.Map<Integer, LogLine>) cacheField.get(list);
		long cachedWeight = cache.values().stream().mapToLong(VirtualLogList::lineWeight).sum();
		var batchField = VirtualLogList.class.getDeclaredField("currentBatch");
		batchField.setAccessible(true);
		var batch = (LogFileModel.Batch) batchField.get(list);
		assertEquals(new VirtualLogList.CacheUsage(cachedWeight,
				batch == null ? 0 : VirtualLogList.batchWeight(batch), cache.size()), list.cacheUsage());
	}

	@Test
	public void readFailureIsNotLogContentAndRewriteClearsIt() throws Exception {
		Path file = tempDir.resolve("oversized.log");
		try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(file.toFile(), "rw")) {
			output.setLength(2 * 1024 * 1024);
		}
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model);
		LogLine error = loaded(list, 0);
		assertTrue(error.isReadError());
		assertEquals("", error.content());
		assertTrue(error.errorMessage().contains("1048576"));
		org.junit.jupiter.api.Assertions.assertSame(error, list.get(0));
		Files.writeString(file, "readable");
		model.updateIndex();
		list.publish(model.snapshot());
		assertFalse(loaded(list, 0).isReadError());
		assertEquals("readable", loaded(list, 0).content());
	}

	@Test
	public void backgroundIndexMustNotChangePublishedSize() throws Exception {
		Path file = tempDir.resolve("publication.log");
		Files.writeString(file, "first");
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model);
		Files.writeString(file, "\nsecond", java.nio.file.StandardOpenOption.APPEND);
		model.updateIndex();
		assertEquals(1, list.size());
		assertEquals("first", loaded(list, 0).content());
		list.fireSizeChanged(1, 2);
		assertEquals(2, list.size());
	}

	@Test
	public void appendNotifiesChangedFinalLineAndAddedRows() throws Exception {
		Path file = tempDir.resolve("notifications.log");
		Files.writeString(file, "first");
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model);
		AtomicBoolean updated = new AtomicBoolean();
		AtomicBoolean added = new AtomicBoolean();
		list.addListener((javafx.collections.ListChangeListener<LogLine>) change -> {
			while (change.next()) {
				if (change.wasUpdated() && change.getFrom() == 0)
					updated.set(true);
				if (change.wasAdded() && change.getFrom() == 1 && change.getTo() == 2)
					added.set(true);
			}
		});
		Files.writeString(file, " completed\nsecond", java.nio.file.StandardOpenOption.APPEND);
		model.updateIndex();
		list.fireSizeChanged(1, 2);
		assertTrue(updated.get());
		assertTrue(added.get());
		assertEquals("first completed", loaded(list, 0).content());
	}

	@Test
	public void testVirtualList() throws Exception {
		Path logFile = tempDir.resolve("test.log");
		Files.writeString(logFile, "Line 1\nLine 2\nLine 3\n");

		LogFileModel model = new LogFileModel(logFile);
		VirtualLogList list = createList(model);

		assertEquals(4, list.size());
		assertEquals("Line 1", loaded(list, 0).content());
		assertEquals(1, list.get(0).lineNumber());
		assertEquals("Line 2", loaded(list, 1).content());
		assertEquals(2, list.get(1).lineNumber());
		assertEquals("Line 3", loaded(list, 2).content());
		assertEquals(3, list.get(2).lineNumber());
		assertEquals("", loaded(list, 3).content());
		assertEquals(4, list.get(3).lineNumber());

		// Test change notification
		AtomicBoolean changed = new AtomicBoolean(false);
		list.addListener((javafx.collections.ListChangeListener<LogLine>) _ -> changed.set(true));

		Files.writeString(logFile, "Line 4\n", java.nio.file.StandardOpenOption.APPEND);
		model.updateIndex();
		list.fireSizeChanged(4, 5);

		assertTrue(changed.get());
		assertEquals(5, list.size());
		assertEquals("Line 4", loaded(list, 3).content());
		assertEquals("", loaded(list, 4).content());
	}

	@Test
	public void sameCountAppendNotifiesAndRefreshesFinalLine() throws Exception {
		Path file = tempDir.resolve("partial.log");
		Files.writeString(file, "prefix");
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model);
		assertEquals("prefix", loaded(list, 0).content());
		AtomicBoolean updated = new AtomicBoolean();
		list.addListener((javafx.collections.ListChangeListener<LogLine>) change -> {
			while (change.next())
				updated.set(updated.get() || change.wasUpdated());
		});
		Files.writeString(file, " suffix", java.nio.file.StandardOpenOption.APPEND);
		model.updateIndex();
		list.publish(model.snapshot());
		assertTrue(updated.get());
		assertEquals("prefix suffix", loaded(list, 0).content());
	}

	@Test
	public void coalescedUpdatesAreRelativeToPublishedStateAndIgnoreStaleVersions() throws Exception {
		Path file = tempDir.resolve("coalesced.log");
		Files.writeString(file, "one");
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model);
		Files.writeString(file, "\ntwo", java.nio.file.StandardOpenOption.APPEND);
		model.updateIndex();
		var intermediate = model.snapshot();
		Files.writeString(file, "\nthree", java.nio.file.StandardOpenOption.APPEND);
		model.updateIndex();
		AtomicBoolean added = new AtomicBoolean();
		list.addListener((javafx.collections.ListChangeListener<LogLine>) change -> {
			while (change.next()) {
				if (change.wasAdded()) {
					assertEquals(1, change.getFrom());
					assertEquals(3, change.getTo());
					assertEquals(3, list.size());
					added.set(true);
				}
			}
		});
		list.publish(model.snapshot());
		list.publish(intermediate);
		assertTrue(added.get());
		assertEquals(3, list.size());
		assertEquals("three", loaded(list, 2).content());
	}

	@Test
	public void replacementReportsValidRemovedAndAddedRanges() throws Exception {
		Path file = tempDir.resolve("removed.log");
		Files.writeString(file, "one\ntwo\nthree");
		LogFileModel model = new LogFileModel(file);
		VirtualLogList list = createList(model);
		assertEquals("two", loaded(list, 1).content());
		AtomicBoolean replaced = new AtomicBoolean();
		list.addListener((javafx.collections.ListChangeListener<LogLine>) change -> {
			while (change.next()) {
				if (change.wasUpdated())
					continue;
				assertTrue(change.wasReplaced());
				assertEquals(0, change.getFrom());
				assertEquals(1, change.getTo());
				assertEquals(3, change.getRemovedSize());
				assertEquals("two", change.getRemoved().get(1).content());
				replaced.set(true);
			}
		});
		Files.writeString(file, "new");
		model.updateIndex();
		list.publish(model.snapshot());
		assertTrue(replaced.get());
		assertEquals("new", loaded(list, 0).content());
	}

	@Test
	public void testVirtualListNoTrailingNewline() throws Exception {
		Path logFile = tempDir.resolve("test2.log");
		Files.writeString(logFile, "Line 1\nLine 2\nLine 3"); // No trailing newline

		LogFileModel model = new LogFileModel(logFile);
		VirtualLogList list = createList(model);

		assertEquals(3, list.size());
		assertEquals("Line 1", loaded(list, 0).content());
		assertEquals("Line 2", loaded(list, 1).content());
		assertEquals("Line 3", loaded(list, 2).content());
	}

	@Test
	public void testTruncation() throws Exception {
		Path logFile = tempDir.resolve("trunc.log");
		Files.writeString(logFile, "Line 1\nLine 2\nLine 3\n");

		LogFileModel model = new LogFileModel(logFile);
		VirtualLogList list = createList(model);

		assertEquals(4, list.size());

		// Truncate
		Files.writeString(logFile, "New Line 1\n");
		model.updateIndex();
		list.fireSizeChanged(4, 2);

		assertEquals(2, list.size());
		assertEquals("New Line 1", loaded(list, 0).content());
		assertEquals(1, list.get(0).lineNumber());
		assertEquals("", loaded(list, 1).content());
	}

	@Test
	public void testTruncationWithSameLineCountClearsCache() throws Exception {
		Path logFile = tempDir.resolve("trunc_same_count.log");
		Files.writeString(logFile, "Line 1\nLine 2\n");

		LogFileModel model = new LogFileModel(logFile);
		VirtualLogList list = createList(model);

		assertEquals(3, list.size());
		assertEquals("Line 1", loaded(list, 0).content());
		assertEquals("Line 2", loaded(list, 1).content());

		// Truncate and rewrite with the same number of lines.
		Files.writeString(logFile, "New 1\nNew 2\n");
		model.updateIndex();
		list.fireSizeChanged(3, 3);

		assertEquals(3, list.size());
		assertEquals("New 1", loaded(list, 0).content());
		assertEquals("New 2", loaded(list, 1).content());
		assertEquals("", loaded(list, 2).content());
	}
}
