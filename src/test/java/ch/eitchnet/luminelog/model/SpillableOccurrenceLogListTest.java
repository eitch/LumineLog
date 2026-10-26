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
import ch.eitchnet.luminelog.service.OccurrencePageService;
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

class SpillableOccurrenceLogListTest {

	@TempDir Path directory;

	private final class Fixture implements AutoCloseable {
		final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
		final LineLoadingService lines = new LineLoadingService(callbacks::add);
		final OccurrencePageService pages = new OccurrencePageService(callbacks::add);
		final Path path;
		final LogFileModel model;
		final VirtualLogList live;
		final SpillableOccurrenceIndex index;
		final OccurrenceLogList results;

		Fixture(int count) throws Exception {
			path = directory.resolve("source.log");
			Files.writeString(path, "match\nskip\n".repeat(count));
			model = new LogFileModel(path);
			live = new VirtualLogList(model, lines);
			try (var builder = new SpillableOccurrenceIndex.Builder(directory.resolve("stores"), 8)) {
				for (int i = 0; i < count; i++)
					builder.add(i * 2);
				index = builder.build();
			}
			results = new OccurrenceLogList(model, live, live.snapshot(), index, lines, pages);
		}

		Runnable completion() throws Exception {
			Runnable result = callbacks.poll(5, TimeUnit.SECONDS);
			assertNotNull(result, "No background completion");
			return result;
		}

		LogLine loaded(int position) throws Exception {
			for (int turn = 0; turn < 20; turn++) {
				LogLine line = results.get(position);
				if (!line.isLoading())
					return line;
				completion().run();
			}
			throw new AssertionError("Occurrence did not settle");
		}

		@Override public void close() {
			results.close();
			live.close();
			pages.close();
			lines.close();
		}
	}

	@Test
	void identifiersLoadBeforeTextAndPreserveOriginalNumbers() throws Exception {
		try (var fixture = new Fixture(10000)) {
			assertTrue(fixture.results.get(5000).isLoading());
			assertEquals(0, fixture.lines.activeJobs() + fixture.lines.queuedJobs());
			assertEquals(0, fixture.results.accountedPageBytes());
			fixture.completion().run();
			assertTrue(fixture.results.accountedPageBytes() > 0);
			assertEquals(new LogLine(10001, "match"), fixture.loaded(5000));
			assertTrue(fixture.callbacks.isEmpty());
		}
	}

	@Test
	void selectionInspectionDoesNotReadUncachedIdentifierPages() throws Exception {
		try (var fixture = new Fixture(20000)) {
			fixture.results.withoutLoading(() -> {
				for (int row = 0; row < fixture.results.size(); row++)
					assertTrue(fixture.results.get(row).isLoading());
			});
			assertEquals(0, fixture.pages.admittedJobs());
			assertEquals(0, fixture.lines.activeJobs() + fixture.lines.queuedJobs());
			assertTrue(fixture.callbacks.isEmpty());
			assertEquals(new LogLine(1, "match"), fixture.loaded(0));
		}
	}

	@Test
	void randomNavigationRespectsPageBudgetAndReloadsEvictedPages() throws Exception {
		try (var fixture = new Fixture(30000)) {
			for (int row : new int[] {0, 4096, 8192, 12288, 16384, 20480, 24576, 29999, 0}) {
				assertEquals(new LogLine(row * 2 + 1, "match"), fixture.loaded(row));
				assertTrue(fixture.results.accountedPageBytes() <= (long) OccurrenceLogList.MAX_CACHED_PAGES
						* SpillableOccurrenceIndex.PAGE_SIZE * Integer.BYTES);
			}
		}
	}

	@Test
	void pageReadFailureIsExplicitAndRetryDoesNotTruncateResults() throws Exception {
		try (var fixture = new Fixture(100)) {
			Path stored = fixture.index.storagePath();
			byte[] bytes = Files.readAllBytes(stored);
			Files.delete(stored);
			assertTrue(fixture.results.get(0).isLoading(), "get must not attempt synchronous disk reads");
			LogLine failed = fixture.loaded(0);
			assertTrue(failed.isReadError());
			assertFalse(failed.isAvailable());
			assertEquals(100, fixture.results.size());
			Files.write(stored, bytes);
			fixture.results.retryUnavailable();
			assertEquals(new LogLine(1, "match"), fixture.loaded(0));
		}
	}

	@Test
	void heldPageSurvivesAppendsButCannotPublishAfterRewrite() throws Exception {
		try (var fixture = new Fixture(100)) {
			assertTrue(fixture.results.get(99).isLoading());
			Runnable held = fixture.completion();
			for (int i = 0; i < 10; i++) {
				Files.writeString(fixture.path, "later\n", StandardOpenOption.APPEND);
				fixture.model.updateIndex();
				fixture.live.publish(fixture.model.snapshot());
			}
			held.run();
			assertEquals(new LogLine(199, "match"), fixture.loaded(99));
			Files.writeString(fixture.path, "replacement");
			fixture.model.updateIndex();
			fixture.live.publish(fixture.model.snapshot());
			assertTrue(fixture.results.get(99).isReadError());
		}
		try (var fixture = new Fixture(100)) {
			assertTrue(fixture.results.get(0).isLoading());
			Runnable held = fixture.completion();
			Files.writeString(fixture.path, "replacement");
			fixture.model.updateIndex();
			fixture.live.publish(fixture.model.snapshot());
			held.run();
			assertTrue(fixture.results.get(0).isReadError());
			assertEquals(0, fixture.results.accountedPageBytes());
		}
	}

	@Test
	void closingDropsHeldPageAndDeletesOwnedStorage() throws Exception {
		try (var fixture = new Fixture(100)) {
			Path stored = fixture.index.storagePath();
			fixture.results.get(0);
			Runnable held = fixture.completion();
			AtomicInteger updates = new AtomicInteger();
			fixture.results.addListener((ListChangeListener<LogLine>) _ -> updates.incrementAndGet());
			fixture.results.close();
			assertFalse(Files.exists(stored));
			held.run();
			assertEquals(0, updates.get());
			assertEquals(0, fixture.results.accountedPageBytes());
		}
	}
}