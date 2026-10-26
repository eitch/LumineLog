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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class SpillableOccurrenceIndexTest {

	@TempDir
	Path root;

	@Test
	public void testThresholdBoundariesAndPages() throws Exception {
		assertEquals(65_536, SpillableOccurrenceIndex.DEFAULT_THRESHOLD_IDENTIFIERS);
		assertEquals(4096, SpillableOccurrenceIndex.PAGE_SIZE);
		for (int threshold : new int[]{0, 1, 4096, 65_536}) {
			for (int count : new int[]{0, Math.max(0, threshold - 1), threshold, threshold + 1}) {
				try (SpillableOccurrenceIndex.Builder builder = new SpillableOccurrenceIndex.Builder(root, threshold)) {
					for (int i = 0; i < count; i++) {
						builder.add(i * 2);
						int[] retained = (int[]) field(builder, "identifiers");
						assertTrue(retained == null || retained.length <= threshold);
					}
					try (SpillableOccurrenceIndex index = builder.build()) {
						assertEquals(count, index.size());
						assertEquals(count > threshold, index.isSpilled());
						assertEquals(count > threshold ? 0 : (long) count * Integer.BYTES, index.accountedBytes());
						assertNull(field(builder, "identifiers"));
						assertNull(field(builder, "buffer"));
						assertNull(field(builder, "storage"));
						if (index.isSpilled()) {
							assertNull(field(index, "identifiers"));
							assertEquals((long) count * Integer.BYTES, Files.size(index.storagePath()));
							assertArrayEquals(Arrays.copyOf(new byte[]{0, 0, 0, 0, 0, 0, 0, 2}, Math.min(count, 2) * Integer.BYTES),
									readPrefix(index.storagePath(), Math.min(count, 2) * Integer.BYTES));
						} else {
							assertNull(index.storagePath());
							assertEquals(count, ((int[]) field(index, "identifiers")).length);
						}
						for (int page = 0; (long) page * SpillableOccurrenceIndex.PAGE_SIZE < count; page++) {
							int start = page * SpillableOccurrenceIndex.PAGE_SIZE;
							int[] values = index.readPage(page);
							assertEquals(Math.min(4096, count - start), values.length);
							for (int j = 0; j < values.length; j++)
								assertEquals((start + j) * 2, values[j]);
							values[0] = -1;
							assertEquals(start * 2, index.readPage(page)[0]);
						}
						assertThrows(IndexOutOfBoundsException.class, () -> index.readPage(-1));
						assertThrows(IndexOutOfBoundsException.class, () -> index.readPage(Integer.MAX_VALUE));
						assertThrows(IndexOutOfBoundsException.class, () -> index.readPage((count + 4095) / 4096));
						assertThrows(IndexOutOfBoundsException.class, () -> index.lineIndex(-1));
						assertThrows(IndexOutOfBoundsException.class, () -> index.lineIndex(count));
					}
				}
				assertRootEmpty();
			}
		}
	}

	@Test
	public void testRandomLookupsAndLowerBoundMatchMemoryIndex() throws Exception {
		Random random = new Random(73);
		int[] expected = new int[12_345];
		ChunkedOccurrenceIndex.Builder reference = new ChunkedOccurrenceIndex.Builder();
		try (SpillableOccurrenceIndex.Builder builder = new SpillableOccurrenceIndex.Builder(root, 31)) {
			int line = 0;
			for (int i = 0; i < expected.length; i++) {
				line += 1 + random.nextInt(4);
				expected[i] = line;
				builder.add(line);
				reference.add(line);
			}
			try (SpillableOccurrenceIndex index = builder.build(); OccurrenceIndex memory = reference.build()) {
				for (int i = 0; i < 2000; i++) {
					int offset = random.nextInt(expected.length);
					assertEquals(memory.lineIndex(offset), index.lineIndex(offset));
					int target = random.nextInt(line + 2);
					assertEquals(memory.lowerBound(target), index.lowerBound(target));
				}
				assertEquals(0, index.lowerBound(Integer.MIN_VALUE));
				assertEquals(expected.length, index.lowerBound(Integer.MAX_VALUE));
				for (int page = 0; page < 4; page++)
					assertArrayEquals(Arrays.copyOfRange(expected, page * 4096, Math.min(expected.length, (page + 1) * 4096)),
							index.readPage(page));
			}
		}
	}

	@Test
	public void testMemoryModeNeverTouchesRootOrReadHooks() throws Exception {
		Path unavailable = root.resolve("not-a-directory");
		Files.writeString(unavailable, "foreign");
		try (SpillableOccurrenceIndex.Builder builder = new SpillableOccurrenceIndex.Builder(unavailable, 2,
				new SpillableOccurrenceIndex.IOHooks() {
					@Override
					public void beforeRead() {
						fail("Memory mode must not read storage");
					}
				})) {
			builder.add(0);
			builder.add(Integer.MAX_VALUE);
			try (SpillableOccurrenceIndex index = builder.build()) {
				assertEquals(Integer.MAX_VALUE, index.lineIndex(1));
				assertEquals(1, index.lowerBound(Integer.MAX_VALUE));
				assertArrayEquals(new int[]{0, Integer.MAX_VALUE}, index.readPage(0));
			}
		}
		assertEquals("foreign", Files.readString(unavailable));
	}

	@Test
	public void testTransferCancellationAndClosedState() throws Exception {
		for (int threshold : new int[]{0, 2}) {
			SpillableOccurrenceIndex.Builder builder = new SpillableOccurrenceIndex.Builder(root, threshold);
			builder.add(2);
			SpillableOccurrenceIndex index = builder.build();
			builder.close();
			assertEquals(2, index.lineIndex(0));
			assertThrows(IllegalStateException.class, () -> builder.add(3));
			assertThrows(IllegalStateException.class, builder::build);
			Path data = index.storagePath();
			index.close();
			index.close();
			assertEquals(0, index.size());
			assertEquals(0, index.accountedBytes());
			assertThrows(IllegalStateException.class, () -> index.lineIndex(0));
			assertThrows(IllegalStateException.class, () -> index.lowerBound(0));
			assertThrows(IllegalStateException.class, () -> index.readPage(0));
			if (data != null)
				assertFalse(Files.exists(data.getParent()));
			assertRootEmpty();
			try (SpillableOccurrenceIndex.Builder cancelled = new SpillableOccurrenceIndex.Builder(root, threshold)) {
				cancelled.add(0);
				cancelled.add(1);
				cancelled.add(2);
			}
			assertRootEmpty();
		}
	}

	@Test
	public void testInvalidInputAndCapacityAbortAndClean() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> new SpillableOccurrenceIndex.Builder(root, -1));
		for (int invalid : new int[]{-1, Integer.MIN_VALUE, 1, 0}) {
			try (SpillableOccurrenceIndex.Builder builder = new SpillableOccurrenceIndex.Builder(root, 0)) {
				builder.add(1);
				assertThrows(IllegalArgumentException.class, () -> builder.add(invalid));
				assertThrows(IllegalStateException.class, builder::build);
				assertRootEmpty();
			}
		}
		try (SpillableOccurrenceIndex.Builder builder = new SpillableOccurrenceIndex.Builder(root, 0)) {
			Field size = builder.getClass().getDeclaredField("size");
			size.setAccessible(true);
			size.setInt(builder, Integer.MAX_VALUE);
			assertThrows(IllegalStateException.class, () -> builder.add(Integer.MAX_VALUE));
			assertRootEmpty();
		}
	}

	@Test
	public void testWriteAndBuildFailuresCleanPartialFiles() throws Exception {
		IOException failure = new IOException("injected storage failure");
		for (boolean failBuild : new boolean[]{false, true}) {
			try (SpillableOccurrenceIndex.Builder builder = new SpillableOccurrenceIndex.Builder(root, 0,
					new SpillableOccurrenceIndex.IOHooks() {
						@Override
						public void beforeWrite() throws IOException {
							if (!failBuild)
								throw failure;
						}

						@Override
						public void beforeBuild() throws IOException {
							if (failBuild)
								throw failure;
						}
					})) {
				builder.add(0);
				if (failBuild) {
					assertSame(failure, assertThrows(IOException.class, builder::build));
				} else {
					for (int i = 1; i < 4096; i++)
						builder.add(i);
					assertSame(failure, assertThrows(IOException.class, () -> builder.add(4096)));
				}
				assertThrows(IllegalStateException.class, builder::build);
				assertRootEmpty();
			}
		}
		Path foreign = root.resolve("foreign");
		Files.writeString(foreign, "untouched");
		try (SpillableOccurrenceIndex.Builder builder = new SpillableOccurrenceIndex.Builder(foreign, 0)) {
			assertThrows(IOException.class, () -> builder.add(0));
			assertThrows(IllegalStateException.class, builder::build);
		}
		assertEquals("untouched", Files.readString(foreign));
	}

	@Test
	public void testReadFailuresAndTruncationAreVisible() throws Exception {
		try (SpillableOccurrenceIndex.Builder builder = new SpillableOccurrenceIndex.Builder(root, 0,
				new SpillableOccurrenceIndex.IOHooks() {
					@Override
					public void beforeRead() throws IOException {
						throw new IOException("injected read failure");
					}
				})) {
			builder.add(0);
			try (SpillableOccurrenceIndex index = builder.build()) {
				assertThrows(IOException.class, () -> index.readPage(0));
				assertThrows(UncheckedIOException.class, () -> index.lineIndex(0));
				assertThrows(UncheckedIOException.class, () -> index.lowerBound(1));
			}
		}
		try (SpillableOccurrenceIndex index = singleSpilled()) {
			try (FileChannel channel = FileChannel.open(index.storagePath(), StandardOpenOption.WRITE)) {
				channel.truncate(3);
			}
			assertThrows(IOException.class, () -> index.readPage(0));
			assertThrows(UncheckedIOException.class, () -> index.lineIndex(0));
		}
		assertRootEmpty();
	}

	@Test
	public void testDeleteFailureIsVisibleAndCloseCanRetry() throws Exception {
		SpillableOccurrenceIndex index = singleSpilled();
		Path obstruction = index.storagePath().getParent().resolve("foreign");
		Files.writeString(obstruction, "do not delete");
		try {
			assertThrows(UncheckedIOException.class, index::close);
			assertEquals("do not delete", Files.readString(obstruction));
			assertEquals(0, index.size());
		} finally {
			Files.delete(obstruction);
			index.close();
		}
		assertRootEmpty();
	}

	@Test
	public void testAbandonedCleanupAndAutomaticSpillCleanup() throws Exception {
		Path abandoned = abandoned("abandoned");
		try (SpillableOccurrenceIndex active = singleSpilled()) {
			assertFalse(Files.exists(abandoned));
			Path activeDirectory = active.storagePath().getParent();
			SpillableOccurrenceIndex.cleanupAbandoned(root);
			assertTrue(Files.exists(active.storagePath()));
			Path another = abandoned("another");
			try (FileChannel owner = FileChannel.open(another.resolve("owner.lock"), StandardOpenOption.WRITE);
					FileLock lock = owner.lock()) {
				assertTrue(lock.isValid());
				SpillableOccurrenceIndex.cleanupAbandoned(root);
				assertTrue(Files.exists(another));
			}
			SpillableOccurrenceIndex.cleanupAbandoned(root);
			assertFalse(Files.exists(another));
			assertTrue(Files.exists(activeDirectory));
		}
		assertRootEmpty();
	}

	@Test
	public void testCleanupPreservesForeignAndSymlinks() throws Exception {
		Path unknown = abandoned("unknown-file");
		Files.writeString(unknown.resolve("foreign"), "untouched");
		Path wrongMarker = abandoned("wrong-marker");
		Files.writeString(wrongMarker.resolve("owner"), "not luminelog");
		Path target = Files.createDirectory(root.resolve("foreign-target"));
		Path keep = Files.writeString(target.resolve("keep"), "untouched");
		Path linkedDirectory = root.resolve("linked-directory");
		Files.createSymbolicLink(linkedDirectory, target);
		Path linkedData = abandoned("linked-data");
		Files.delete(linkedData.resolve("occurrences.bin"));
		Files.createSymbolicLink(linkedData.resolve("occurrences.bin"), keep);
		Path linkedMarker = abandoned("linked-marker");
		Path markerTarget = Files.writeString(root.resolve("marker-target"), "LumineLog occurrence storage v1\n");
		Files.delete(linkedMarker.resolve("owner"));
		Files.createSymbolicLink(linkedMarker.resolve("owner"), markerTarget);
		SpillableOccurrenceIndex.cleanupAbandoned(root);
		for (Path path : new Path[]{unknown, wrongMarker, linkedDirectory, linkedData, linkedMarker, markerTarget})
			assertTrue(Files.exists(path), path.toString());
		assertEquals("untouched", Files.readString(keep));
		assertThrows(IOException.class, () -> SpillableOccurrenceIndex.cleanupAbandoned(linkedDirectory));
	}

	@Test
	public void testCloseWaitsForInFlightRead() throws Exception {
		CountDownLatch reading = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2);
				SpillableOccurrenceIndex.Builder builder = new SpillableOccurrenceIndex.Builder(root, 0,
						new SpillableOccurrenceIndex.IOHooks() {
							@Override
							public void beforeRead() throws IOException {
								reading.countDown();
								try {
									if (!release.await(5, TimeUnit.SECONDS))
										throw new IOException("Timed out awaiting test read release");
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
									throw new IOException(e);
								}
							}
						})) {
			builder.add(17);
			try (SpillableOccurrenceIndex index = builder.build()) {
				var read = executor.submit(() -> index.readPage(0));
				assertTrue(reading.await(5, TimeUnit.SECONDS));
				var closed = executor.submit(index::close);
				try {
					closed.get(2, TimeUnit.SECONDS);
					assertFalse(Files.exists(index.storagePath()));
					assertEquals(0, index.size());
				} finally {
					release.countDown();
				}
				assertArrayEquals(new int[]{17}, read.get(5, TimeUnit.SECONDS));
				closed.get(5, TimeUnit.SECONDS);
				assertFalse(Files.exists(index.storagePath()));
			}
		} finally {
			release.countDown();
		}
	}

	@Test
	public void testConcurrentBuildersCleanupPreservesOwners() throws Exception {
		try (var executor = Executors.newFixedThreadPool(4)) {
			var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
			for (int worker = 0; worker < 4; worker++) {
				tasks.add(executor.submit(() -> {
					for (int iteration = 0; iteration < 30; iteration++) {
						try (SpillableOccurrenceIndex index = singleSpilled()) {
							SpillableOccurrenceIndex.cleanupAbandoned(root);
							assertEquals(17, index.lineIndex(0));
						}
					}
					return null;
				}));
			}
			for (var task : tasks)
				task.get(15, TimeUnit.SECONDS);
		}
		assertRootEmpty();
	}

	@Test
	public void testMissingFileCanBeRestoredAndRetried() throws Exception {
		try (SpillableOccurrenceIndex index = singleSpilled()) {
			Path original = index.storagePath();
			byte[] content = Files.readAllBytes(original);
			Files.delete(original);
			assertThrows(IOException.class, () -> index.readPage(0));
			assertThrows(UncheckedIOException.class, () -> index.lineIndex(0));
			assertThrows(UncheckedIOException.class, () -> index.lowerBound(17));
			Files.write(original, content);
			assertEquals(original, index.storagePath());
			assertArrayEquals(new int[]{17}, index.readPage(0));
			assertEquals(17, index.lineIndex(0));
			assertEquals(0, index.lowerBound(17));
		}
		assertRootEmpty();
	}

	private SpillableOccurrenceIndex singleSpilled() throws IOException {
		try (SpillableOccurrenceIndex.Builder builder = new SpillableOccurrenceIndex.Builder(root, 0)) {
			builder.add(17);
			return builder.build();
		}
	}

	private Path abandoned(String name) throws IOException {
		Path directory = Files.createDirectory(root.resolve(name));
		Files.writeString(directory.resolve("owner"), "LumineLog occurrence storage v1\n");
		Files.createFile(directory.resolve("owner.lock"));
		Files.write(directory.resolve("occurrences.bin"), new byte[4]);
		return directory;
	}

	private void assertRootEmpty() throws IOException {
		try (var files = Files.list(root)) {
			assertEquals(0, files.count());
		}
	}

	private static byte[] readPrefix(Path path, int length) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			ByteBuffer buffer = ByteBuffer.allocate(length);
			while (buffer.hasRemaining())
				assertTrue(channel.read(buffer) > 0);
			return buffer.array();
		}
	}

	private static Object field(Object instance, String name) throws ReflectiveOperationException {
		Field field = instance.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(instance);
	}
}