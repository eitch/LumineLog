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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class ChunkedOccurrenceIndexTest {

	@Test
	public void testEmptyAndClosed() throws ReflectiveOperationException {
		OccurrenceIndex index = new ChunkedOccurrenceIndex.Builder().build();
		assertEquals(0, index.size());
		assertEquals(0, index.accountedBytes());
		assertEquals(0, index.lowerBound(Integer.MIN_VALUE));
		assertEquals(0, index.lowerBound(Integer.MAX_VALUE));
		assertThrows(IndexOutOfBoundsException.class, () -> index.lineIndex(0));
		index.close();
		index.close();
		assertEquals(0, index.size());
		assertEquals(0, index.accountedBytes());
		assertThrows(IllegalStateException.class, () -> index.lineIndex(0));
		assertThrows(IllegalStateException.class, () -> index.lowerBound(0));
		assertNull(field(index, "chunks"));
	}

	@Test
	public void testChunkBoundariesAndResourceCounts() throws ReflectiveOperationException {
		assertEquals(4096, ChunkedOccurrenceIndex.CHUNK_SIZE);
		for (int count : new int[]{1, 4095, 4096, 4097, 8192, 8193}) {
			ChunkedOccurrenceIndex.Builder builder = new ChunkedOccurrenceIndex.Builder();
			for (int i = 0; i < count; i++)
				builder.add(i * 2);
			try (ChunkedOccurrenceIndex index = builder.build()) {
				assertEquals(count, index.size());
				assertEquals((long) count * Integer.BYTES, index.accountedBytes());
				int[][] chunks = (int[][]) field(index, "chunks");
				assertEquals((count + 4095) / 4096, chunks.length);
				int retainedInts = 0;
				for (int[] chunk : chunks)
					retainedInts += chunk.length;
				assertEquals(count, retainedInts);
				for (int i = 0; i < count; i++)
					assertEquals(i * 2, index.lineIndex(i));
				assertThrows(IndexOutOfBoundsException.class, () -> index.lineIndex(-1));
				assertThrows(IndexOutOfBoundsException.class, () -> index.lineIndex(count));
				assertThrows(IndexOutOfBoundsException.class, () -> index.lineIndex(Integer.MAX_VALUE));
			}
		}
	}

	@Test
	public void testOwnershipTransferAndNoBuilderReuse() throws ReflectiveOperationException {
		ChunkedOccurrenceIndex.Builder builder = new ChunkedOccurrenceIndex.Builder();
		for (int i = 0; i < 4097; i++)
			builder.add(i);
		List<?> originalChunks = (List<?>) field(builder, "chunks");
		Object fullChunk = originalChunks.getFirst();
		Object partialChunk = originalChunks.getLast();
		ChunkedOccurrenceIndex index = builder.build();
		int[][] owned = (int[][]) field(index, "chunks");
		assertSame(fullChunk, owned[0]);
		assertNotSame(partialChunk, owned[1]);
		assertEquals(1, owned[1].length);
		assertNull(field(builder, "chunks"));
		assertThrows(IllegalStateException.class, () -> builder.add(4097));
		assertThrows(IllegalStateException.class, builder::build);
		assertEquals(4096, index.lineIndex(4096));
		index.close();
		assertNull(field(index, "chunks"));
		assertEquals(0, index.size());
		assertEquals(0, index.accountedBytes());
		assertThrows(IllegalStateException.class, () -> index.lineIndex(-1));
		assertThrows(IllegalStateException.class, () -> index.lowerBound(4096));
		assertThrows(IllegalStateException.class, builder::build);
	}

	@Test
	public void testInvalidInputDoesNotChangeBuilder() {
		ChunkedOccurrenceIndex.Builder builder = new ChunkedOccurrenceIndex.Builder();
		assertThrows(IllegalArgumentException.class, () -> builder.add(-1));
		assertThrows(IllegalArgumentException.class, () -> builder.add(Integer.MIN_VALUE));
		builder.add(0);
		builder.add(5);
		assertThrows(IllegalArgumentException.class, () -> builder.add(5));
		assertThrows(IllegalArgumentException.class, () -> builder.add(4));
		builder.add(Integer.MAX_VALUE);
		try (OccurrenceIndex index = builder.build()) {
			assertEquals(3, index.size());
			assertEquals(Integer.MAX_VALUE, index.lineIndex(2));
			assertEquals(2, index.lowerBound(Integer.MAX_VALUE));
		}
	}

	@Test
	public void testCapacityGuardWithoutHugeAllocation() throws ReflectiveOperationException {
		ChunkedOccurrenceIndex.Builder builder = new ChunkedOccurrenceIndex.Builder();
		Field size = builder.getClass().getDeclaredField("size");
		size.setAccessible(true);
		size.setInt(builder, Integer.MAX_VALUE);
		assertThrows(IllegalStateException.class, () -> builder.add(Integer.MAX_VALUE));
		assertTrue(((List<?>) field(builder, "chunks")).isEmpty());
		assertEquals(Integer.MAX_VALUE, size.getInt(builder));
	}

	@Test
	public void testLowerBoundAcrossChunksAgainstSortedReference() {
		Random random = new Random(7);
		int[] lines = new int[12345];
		ChunkedOccurrenceIndex.Builder builder = new ChunkedOccurrenceIndex.Builder();
		int line = 0;
		for (int i = 0; i < lines.length; i++) {
			line += 1 + random.nextInt(4);
			lines[i] = line;
			builder.add(line);
		}
		try (OccurrenceIndex index = builder.build()) {
			assertEquals(0, index.lowerBound(Integer.MIN_VALUE));
			int expected = 0;
			for (int target = -1; target <= line + 1; target++) {
				while (expected < lines.length && lines[expected] < target)
					expected++;
				assertEquals(expected, index.lowerBound(target), "target=" + target);
			}
			assertEquals(lines.length, index.lowerBound(Integer.MAX_VALUE));
		}
	}

	private static Object field(Object instance, String name) throws ReflectiveOperationException {
		Field field = instance.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(instance);
	}
}