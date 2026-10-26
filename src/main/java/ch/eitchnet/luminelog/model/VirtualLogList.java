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

import javafx.collections.ObservableListBase;

import java.io.IOException;
import java.util.AbstractList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VirtualLogList extends ObservableListBase<LogLine> {

	public static final long DEFAULT_CACHE_BYTES = 8 * 1024 * 1024;
	public static final int DEFAULT_CACHE_ENTRIES = 1000;
	private static final long ENTRY_OVERHEAD_BYTES = 192;
	private static final long STRING_OVERHEAD_BYTES = 64;
	private static final long BATCH_OVERHEAD_BYTES = 128;
	private static final int REFERENCE_BYTES = 8;

	private final LogFileModel logModel;
	private final Thread owner = Thread.currentThread();
	private LogFileModel.Snapshot snapshot;
	private final Map<Integer, LogLine> cache;
	private final long byteBudget;
	private final int entryLimit;
	private long cachedBytes;
	private long batchBytes;

	private LogFileModel.Batch currentBatch;

	public VirtualLogList(LogFileModel logModel) {
		this(logModel, DEFAULT_CACHE_BYTES, DEFAULT_CACHE_ENTRIES);
	}

	VirtualLogList(LogFileModel logModel, long byteBudget, int entryLimit) {
		if (byteBudget < 0 || entryLimit < 1)
			throw new IllegalArgumentException("Cache budget must be nonnegative and entry limit positive");
		this.logModel = logModel;
		this.snapshot = logModel.snapshot();
		this.byteBudget = byteBudget;
		this.entryLimit = entryLimit;
		this.cache = new LinkedHashMap<>(16, 0.75f, true);
	}

	@Override
	public LogLine get(int index) {
		if (index < 0 || index >= size())
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());

		LogLine cached = cache.get(index);
		if (cached != null)
			return cached;

		LogFileModel.Batch batch = currentBatch;
		if (batch == null || index < batch.startLine() || index >= batch.endLine()) {
			currentBatch = null;
			batchBytes = 0;
			try {
				batch = snapshot.readBatch(index);
			} catch (IOException e) {
				LogLine error = LogLine.readError(index + 1, e.getMessage());
				if (e instanceof LogFileModel.OversizedLineException)
					cacheLine(index, error);
				return error;
			}
			long weight = batchWeight(batch);
			if (weight <= byteBudget) {
				currentBatch = batch;
				batchBytes = weight;
				trimCache();
			}
			List<String> lines = batch.getLines();
			for (int i = 0; i < lines.size(); i++) {
				int lineIndex = batch.startLine() + i;
				if (lineIndex != index && !cache.containsKey(lineIndex))
					cacheLine(lineIndex, new LogLine(lineIndex + 1, lines.get(i)));
			}
		}

		LogLine result = new LogLine(index + 1, batch.getLines().get(index - batch.startLine()));
		cacheLine(index, result);
		return result;
	}

	private void cacheLine(int index, LogLine line) {
		long weight = lineWeight(line);
		if (weight > byteBudget - batchBytes)
			return;
		removeCachedLine(index);
		cache.put(index, line);
		cachedBytes += weight;
		trimCache();
	}

	private void removeCachedLine(int index) {
		LogLine removed = cache.remove(index);
		if (removed != null)
			cachedBytes -= lineWeight(removed);
	}

	private void trimCache() {
		var entries = cache.entrySet().iterator();
		while (cache.size() > entryLimit || cachedBytes > byteBudget - batchBytes) {
			cachedBytes -= lineWeight(entries.next().getValue());
			entries.remove();
		}
	}

	// Deliberately charge shared strings twice; these are conservative weights, not measured heap sizes.
	static long lineWeight(LogLine line) {
		return ENTRY_OVERHEAD_BYTES + stringWeight(line.content())
				+ (line.errorMessage() == null ? 0 : stringWeight(line.errorMessage()));
	}

	private static long stringWeight(String value) {
		return STRING_OVERHEAD_BYTES + aligned(2L * value.length());
	}

	private static long aligned(long bytes) {
		return (bytes + 7) & ~7L;
	}

	static long batchWeight(LogFileModel.Batch batch) {
		long weight = BATCH_OVERHEAD_BYTES + aligned(batch.totalBytesToRead())
				+ (long) REFERENCE_BYTES * batch.getLines().size();
		for (String line : batch.getLines())
			weight += stringWeight(line);
		return weight;
	}

	CacheUsage cacheUsage() {
		return new CacheUsage(cachedBytes, batchBytes, cache.size());
	}

	record CacheUsage(long cachedBytes, long batchBytes, int entries) {
		long totalBytes() {
			return cachedBytes + batchBytes;
		}
	}

	@Override
	public int size() {
		return snapshot.lineCount();
	}

	public void fireSizeChanged(int oldSize, int newSize) {
		publish(logModel.snapshot());
	}

	public LogFileModel.Snapshot snapshot() {
		return snapshot;
	}

	public void publish(LogFileModel.Snapshot update) {
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("Publish log updates on the list's owning thread");
		if (update.version() <= snapshot.version())
			return;
		LogFileModel.Snapshot previous = snapshot;
		int oldSize = size();
		boolean reset = previous.generation() != update.generation();
		Map<Integer, LogLine> removedCache = reset ? new LinkedHashMap<>(cache) : Map.of();
		snapshot = update;
		currentBatch = null;
		batchBytes = 0;
		beginChange();
		try {
			if (reset) {
				cache.clear();
				cachedBytes = 0;
				List<LogLine> removed = new AbstractList<>() {
					@Override
					public int size() {
						return oldSize;
					}

					@Override
					public LogLine get(int index) {
						java.util.Objects.checkIndex(index, oldSize);
						LogLine line = removedCache.get(index);
						if (line != null)
							return line;
						try {
							return new LogLine(index + 1, previous.readBatch(index).getLines().getFirst());
						} catch (IOException e) {
							return LogLine.readError(index + 1, e.getMessage());
						}
					}
				};
				nextReplace(0, size(), removed);
			} else {
				if (oldSize > 0) {
					removeCachedLine(oldSize - 1);
					nextUpdate(oldSize - 1);
				}
				if (size() > oldSize)
					nextAdd(oldSize, size());
			}
		} finally {
			endChange();
		}
	}
}
