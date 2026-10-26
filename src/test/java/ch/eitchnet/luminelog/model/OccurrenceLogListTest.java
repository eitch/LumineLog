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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OccurrenceLogListTest {

	@TempDir
	Path directory;

	private OccurrenceIndex identifiers(int... lines) {
		var builder = new ChunkedOccurrenceIndex.Builder();
		for (int line : lines)
			builder.add(line);
		return builder.build();
	}

	private LogLine loaded(OccurrenceLogList results, int row, LinkedBlockingQueue<Runnable> callbacks)
			throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (results.get(row).isLoading()) {
			Runnable callback = callbacks.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			assertNotNull(callback, "No row completion");
			callback.run();
		}
		return results.get(row);
	}

	@Test
	void lazyRowsKeepOriginalNumbersAndTranslateUpdateRanges() throws Exception {
		Path path = directory.resolve("source.log");
		Files.writeString(path, "skip\nmatch one\nskip\nmatch two");
		var model = new LogFileModel(path);
		var callbacks = new LinkedBlockingQueue<Runnable>();
		try (var loader = new LineLoadingService(callbacks::add)) {
			var live = new VirtualLogList(model, loader);
			try (var results = new OccurrenceLogList(model, live, live.snapshot(), identifiers(1, 3), loader)) {
				assertEquals(2, results.size());
				AtomicInteger updates = new AtomicInteger();
				results.addListener((ListChangeListener<LogLine>) change -> {
					while (change.next()) {
						assertTrue(change.wasUpdated());
						assertEquals(updates.get(), change.getFrom());
						assertEquals(updates.get() + 1, change.getTo());
						updates.incrementAndGet();
					}
				});
				assertTrue(results.get(0).isLoading());
				assertNull(results.get(0).content());
				assertEquals(2, results.get(0).lineNumber());
				assertEquals(new LogLine(2, "match one"), loaded(results, 0, callbacks));
				assertEquals(new LogLine(4, "match two"), loaded(results, 1, callbacks));
				assertEquals(2, updates.get());
			}
			live.close();
		}
	}

	@Test
	void appendKeepsCapturedFinalLineAndDoesNotAddNewMatches() throws Exception {
		Path path = directory.resolve("append.log");
		Files.writeString(path, "skip\nmatch");
		var model = new LogFileModel(path);
		var callbacks = new LinkedBlockingQueue<Runnable>();
		try (var loader = new LineLoadingService(callbacks::add)) {
			var live = new VirtualLogList(model, loader);
			try (var results = new OccurrenceLogList(model, live, live.snapshot(), identifiers(1), loader)) {
				Files.writeString(path, " extended\nmatch new", StandardOpenOption.APPEND);
				model.updateIndex();
				live.publish(model.snapshot());
				assertEquals(1, results.size());
				assertEquals(new LogLine(2, "match"), loaded(results, 0, callbacks));
			}
			live.close();
		}
	}

	@Test
	void heldCompletionSurvivesRepeatedAppendsWithoutRefreshingLoadedRows() throws Exception {
		Path path = directory.resolve("held-append.log");
		Files.writeString(path, "skip\nmatch captured");
		var model = new LogFileModel(path);
		var callbacks = new LinkedBlockingQueue<Runnable>();
		var loader = new LineLoadingService(callbacks::add);
		var live = new VirtualLogList(model, loader);
		try (loader; var results = new OccurrenceLogList(model, live, live.snapshot(), identifiers(1), loader)) {
			AtomicInteger updates = new AtomicInteger();
			results.addListener((ListChangeListener<LogLine>) change -> {
				while (change.next()) {
					assertTrue(change.wasUpdated());
					assertEquals(0, change.getFrom());
					assertEquals(1, change.getTo());
					updates.incrementAndGet();
				}
			});
			assertTrue(results.get(0).isLoading());
			Runnable completion = callbacks.poll(5, TimeUnit.SECONDS);
			assertNotNull(completion);
			for (int append = 0; append < 20; append++) {
				Files.writeString(path, append == 0 ? " extended\n" : "match new\n", StandardOpenOption.APPEND);
				model.updateIndex();
				live.publish(model.snapshot());
				assertTrue(results.get(0).isLoading());
				assertEquals(1, results.size());
				assertEquals(0, updates.get(), "Appends must not publish occurrence changes");
			}
			completion.run();
			assertEquals(new LogLine(2, "match captured"), results.get(0));
			assertEquals(1, updates.get(), "The original request must complete exactly once");
			Files.writeString(path, "match later\n", StandardOpenOption.APPEND);
			model.updateIndex();
			live.publish(model.snapshot());
			assertEquals(new LogLine(2, "match captured"), results.get(0));
			assertEquals(1, updates.get());
			assertTrue(callbacks.isEmpty(), "Loaded occurrences must not schedule replacement reads");
		} finally {
			live.close();
		}
	}

	@Test
	void loadingOneMatchDoesNotCacheOrNotifyUnrequestedNeighboringMatches() throws Exception {
		Path path = directory.resolve("neighbors.log");
		Files.writeString(path, "match first\nskip\nmatch middle\nskip\nmatch last");
		var model = new LogFileModel(path);
		var callbacks = new LinkedBlockingQueue<Runnable>();
		var loader = new LineLoadingService(callbacks::add);
		var live = new VirtualLogList(model, loader);
		try (loader; var results = new OccurrenceLogList(model, live, live.snapshot(), identifiers(0, 2, 4), loader)) {
			var updatedRows = new java.util.ArrayList<Integer>();
			results.addListener((ListChangeListener<LogLine>) change -> {
				while (change.next()) {
					assertTrue(change.wasUpdated());
					for (int row = change.getFrom(); row < change.getTo(); row++)
						updatedRows.add(row);
				}
			});
			assertEquals(new LogLine(1, "match first"), loaded(results, 0, callbacks));
			assertEquals(java.util.List.of(0), updatedRows);
			assertTrue(results.get(2).isLoading(), "A neighboring match must require its own demand");
			assertEquals(new LogLine(5, "match last"), loaded(results, 2, callbacks));
			assertEquals(java.util.List.of(0, 2), updatedRows);
			assertTrue(results.get(1).isLoading(), "An unrequested match must not be retained with its batch");
			assertEquals(new LogLine(3, "match middle"), loaded(results, 1, callbacks));
			assertEquals(java.util.List.of(0, 2, 1), updatedRows);
			assertEquals(new LogLine(1, "match first"), results.get(0));
			assertEquals(new LogLine(5, "match last"), results.get(2));
		} finally {
			live.close();
		}
	}

	@Test
	void sparseVisibleMatchesSettleWhileSourceKeepsAppending() throws Exception {
		for (boolean allMatch : new boolean[]{false, true})
			assertVisibleMatchesSettle(allMatch);
	}

	private void assertVisibleMatchesSettle(boolean allMatch) throws Exception {
		Path path = directory.resolve("busy-sparse.log");
		StringBuilder text = new StringBuilder();
		int[] matches = new int[12];
		for (int i = 0; i < 24000; i++) {
			if (i % 2000 == 0)
				matches[i / 2000] = i;
			text.append(allMatch || i % 2000 == 0 ? "match\n" : "skip\n");
		}
		Files.writeString(path, text);
		var model = new LogFileModel(path);
		var callbacks = new LinkedBlockingQueue<Runnable>();
		try (var loader = new LineLoadingService(callbacks::add)) {
			var live = new VirtualLogList(model, loader);
			var builder = new ChunkedOccurrenceIndex.Builder();
			for (int i = 0; i < 24000; i++) {
				if (allMatch || i % 2000 == 0)
					builder.add(i);
			}
			try (var results = new OccurrenceLogList(model, live, live.snapshot(), builder.build(), loader)) {
				AtomicInteger updates = new AtomicInteger();
				results.addListener((ListChangeListener<LogLine>) _ -> updates.incrementAndGet());
				boolean settled = false;
				for (int turn = 0; turn < 40; turn++) {
					Files.writeString(path, "new line\n", StandardOpenOption.APPEND);
					model.updateIndex();
					live.publish(model.snapshot());
					settled = true;
					for (int row = 0; row < matches.length; row++)
						settled &= results.get(allMatch ? matches[row] : row).isAvailable();
					if (settled)
						break;
					Runnable callback = callbacks.poll(5, TimeUnit.SECONDS);
					assertNotNull(callback, "Visible occurrence loads must make progress");
					callback.run();
				}
				assertTrue(settled, "Visible sparse matches must settle rather than repeatedly cancel/evict each other");
				int settledUpdates = updates.get();
				for (int append = 0; append < 20; append++) {
					Files.writeString(path, "new line\n", StandardOpenOption.APPEND);
					model.updateIndex();
					live.publish(model.snapshot());
					for (int row = 0; row < matches.length; row++)
						assertEquals(new LogLine(matches[row] + 1, "match"), results.get(allMatch ? matches[row] : row));
				}
				assertEquals(settledUpdates, updates.get(), "Appends must not refresh loaded occurrences");
			}
			live.close();
		}
	}

	@Test
	void rewriteDiscardsCachedTextAndPendingCompletions() throws Exception {
		Path path = directory.resolve("rewrite.log");
		Files.writeString(path, "match old\nmatch old two");
		var model = new LogFileModel(path);
		var callbacks = new LinkedBlockingQueue<Runnable>();
		try (var loader = new LineLoadingService(callbacks::add)) {
			var live = new VirtualLogList(model, loader);
			try (var results = new OccurrenceLogList(model, live, live.snapshot(), identifiers(0, 1), loader)) {
				assertEquals("match old", loaded(results, 0, callbacks).content());
				Files.writeString(path, "replacement");
				model.updateIndex();
				live.publish(model.snapshot());
				assertTrue(results.get(0).isReadError());
				assertTrue(results.get(1).isReadError());
				assertFalse(results.get(0).isAvailable());
				assertEquals("", results.get(0).content());
			}
			live.close();
		}
	}

	@Test
	void closeDropsPendingDeliveryAndReleasesIdentifiers() throws Exception {
		Path path = directory.resolve("close.log");
		Files.writeString(path, "match");
		var model = new LogFileModel(path);
		var callbacks = new LinkedBlockingQueue<Runnable>();
		try (var loader = new LineLoadingService(callbacks::add)) {
			var live = new VirtualLogList(model, loader);
			var index = identifiers(0);
			var results = new OccurrenceLogList(model, live, live.snapshot(), index, loader);
			assertTrue(results.get(0).isLoading());
			Runnable pending = callbacks.poll(5, TimeUnit.SECONDS);
			assertNotNull(pending);
			AtomicInteger updates = new AtomicInteger();
			results.addListener((ListChangeListener<LogLine>) _ -> updates.incrementAndGet());
			results.close();
			pending.run();
			assertEquals(0, updates.get());
			assertEquals(0, index.size());
			live.close();
		}
	}

	@Test
	void rewriteSuppressesHeldReadAndInvalidatesWithOneLazyChange() throws Exception {
		Path path = directory.resolve("pending.log");
		Files.writeString(path, "match\nmatch two");
		var model = new LogFileModel(path);
		var callbacks = new LinkedBlockingQueue<Runnable>();
		try (var loader = new LineLoadingService(callbacks::add)) {
			var live = new VirtualLogList(model, loader);
			try (var results = new OccurrenceLogList(model, live, live.snapshot(), identifiers(0, 1), loader)) {
				assertTrue(results.get(0).isLoading());
				Runnable pending = callbacks.poll(5, TimeUnit.SECONDS);
				assertNotNull(pending);
				AtomicInteger changes = new AtomicInteger();
				results.addListener((ListChangeListener<LogLine>) change -> {
					assertTrue(change.next());
					assertTrue(change.wasReplaced());
					assertEquals(0, change.getFrom());
					assertEquals(2, change.getTo());
					assertEquals(2, change.getRemovedSize());
					assertEquals(2, change.getRemoved().get(1).lineNumber());
					assertTrue(change.getRemoved().get(1).isReadError());
					assertFalse(change.next());
					changes.incrementAndGet();
				});
				Files.writeString(path, "replacement");
				model.updateIndex();
				live.publish(model.snapshot());
				pending.run();
				assertEquals(1, changes.get());
				assertTrue(results.get(0).isReadError());
			}
			live.close();
		}
	}

	@Test
	void replacementBeforeFirstReadFailsRatherThanLoadingNewText() throws Exception {
		Path path = directory.resolve("unread.log");
		Files.writeString(path, "match old");
		var model = new LogFileModel(path);
		var callbacks = new LinkedBlockingQueue<Runnable>();
		try (var loader = new LineLoadingService(callbacks::add)) {
			var live = new VirtualLogList(model, loader);
			try (var results = new OccurrenceLogList(model, live, live.snapshot(), identifiers(0), loader)) {
				Files.delete(path);
				Files.writeString(path, "replacement text");
				assertTrue(loaded(results, 0, callbacks).isReadError());
				assertFalse(results.get(0).isAvailable());
				assertEquals("", results.get(0).content());
			}
			live.close();
		}
	}
}