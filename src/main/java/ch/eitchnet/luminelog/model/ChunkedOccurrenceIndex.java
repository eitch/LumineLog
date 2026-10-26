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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/** Immutable contents; callers must coordinate close with readers. */
public final class ChunkedOccurrenceIndex implements OccurrenceIndex {

	public static final int CHUNK_SIZE = 4096;

	private int[][] chunks;
	private final int count;

	private ChunkedOccurrenceIndex(int[][] chunks, int count) {
		this.chunks = chunks;
		this.count = count;
	}

	@Override
	public int size() {
		return chunks == null ? 0 : count;
	}

	@Override
	public int lineIndex(int resultIndex) {
		ensureOpen();
		Objects.checkIndex(resultIndex, count);
		return chunks[resultIndex / CHUNK_SIZE][resultIndex % CHUNK_SIZE];
	}

	@Override
	public int lowerBound(int originalLineIndex) {
		ensureOpen();
		int low = 0;
		int high = count;
		while (low < high) {
			int middle = low + ((high - low) >>> 1);
			if (chunks[middle / CHUNK_SIZE][middle % CHUNK_SIZE] < originalLineIndex)
				low = middle + 1;
			else
				high = middle;
		}
		return low;
	}

	@Override
	public long accountedBytes() {
		return (long) size() * Integer.BYTES;
	}

	@Override
	public void close() {
		chunks = null;
	}

	private void ensureOpen() {
		if (chunks == null)
			throw new IllegalStateException("Occurrence index is closed");
	}

	/** Single-use, non-thread-safe builder; build transfers ownership of its full chunks. */
	public static final class Builder {

		private ArrayList<int[]> chunks = new ArrayList<>();
		private int size;
		private int last = -1;

		public void add(int originalLineIndex) {
			ensureActive();
			if (originalLineIndex < 0 || originalLineIndex <= last)
				throw new IllegalArgumentException("Line identifiers must be nonnegative and strictly ascending");
			if (size == Integer.MAX_VALUE)
				throw new IllegalStateException("Occurrence index capacity exceeded");
			int offset = size % CHUNK_SIZE;
			if (offset == 0)
				chunks.add(new int[CHUNK_SIZE]);
			chunks.getLast()[offset] = originalLineIndex;
			size++;
			last = originalLineIndex;
		}

		public ChunkedOccurrenceIndex build() {
			ensureActive();
			int remainder = size % CHUNK_SIZE;
			if (remainder != 0)
				chunks.set(chunks.size() - 1, Arrays.copyOf(chunks.getLast(), remainder));
			ChunkedOccurrenceIndex index = new ChunkedOccurrenceIndex(chunks.toArray(int[][]::new), size);
			chunks = null;
			return index;
		}

		private void ensureActive() {
			if (chunks == null)
				throw new IllegalStateException("Builder has already transferred ownership");
		}
	}
}