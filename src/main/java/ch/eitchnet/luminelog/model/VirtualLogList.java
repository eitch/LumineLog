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
import javafx.application.Platform;
import ch.eitchnet.luminelog.service.LineLoadingService;

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
	private final LineLoadingService loader;
	private boolean closed;
	private final Map<Integer, PendingRead> pending = new LinkedHashMap<>();
	public static final int MAX_PENDING_READS = 2;
	public static final int NEARBY_REQUEST_LINES = 64;
	public static final int RETRY_DELAY_MILLIS = 100;
	private javafx.animation.PauseTransition retry;
	private int retryIndex;

	private static class PendingRead {
		private int validEnd;

		private PendingRead(int validEnd) {
			this.validEnd = validEnd;
		}
	}

	private static class SharedLoader {
		private static final LineLoadingService INSTANCE = new LineLoadingService(Platform::runLater);
	}

	public VirtualLogList(LogFileModel logModel) {
		this(logModel, SharedLoader.INSTANCE);
	}

	public VirtualLogList(LogFileModel logModel, LineLoadingService loader) {
		this(logModel, DEFAULT_CACHE_BYTES, DEFAULT_CACHE_ENTRIES, loader);
	}

	VirtualLogList(LogFileModel logModel, long byteBudget, int entryLimit) {
		this(logModel, byteBudget, entryLimit, SharedLoader.INSTANCE);
	}

	VirtualLogList(LogFileModel logModel, long byteBudget, int entryLimit, LineLoadingService loader) {
		if (byteBudget < 0 || entryLimit < 1)
			throw new IllegalArgumentException("Cache budget must be nonnegative and entry limit positive");
		this.logModel = logModel;
		this.loader = loader;
		this.snapshot = logModel.snapshot();
		this.byteBudget = byteBudget;
		this.entryLimit = entryLimit;
		this.cache = new LinkedHashMap<>(16, 0.75f, true);
	}

	@Override
	public LogLine get(int index) {
		checkOwner();
		if (index < 0 || index >= size())
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());

		LogLine cached = cache.get(index);
		if (cached != null)
			return cached;

		if (currentBatch != null && index >= currentBatch.startLine() && index < currentBatch.endLine()) {
			LogLine result = new LogLine(index + 1, currentBatch.getLines().get(index - currentBatch.startLine()));
			cacheLine(index, result);
			return result;
		}
		if (closed)
			return LogLine.readError(index + 1, "Log view closed");
		if (byteBudget < lineWeight(LogLine.readError(index + 1, "Cache budget too small")))
			return LogLine.readError(index + 1, "Cache budget too small");
		request(index, false);
		return LogLine.loading(index + 1);
	}

	private void request(int index, boolean prefetch) {
		if (pending.containsKey(index))
			return;
		if (!prefetch && pending.keySet().stream().anyMatch(start -> index >= start
				&& index < start + NEARBY_REQUEST_LINES && index < pending.get(start).validEnd))
			return;
		if (pending.size() >= MAX_PENDING_READS) {
			if (prefetch)
				return;
			// A jump takes priority over obsolete viewport work.
			loader.cancel(this);
			pending.clear();
		}
		LogFileModel.Snapshot requested = snapshot;
		PendingRead read = new PendingRead(requested.lineCount());
		pending.put(index, read);
		if (!loader.request(this, requested, index, prefetch, result -> {
			checkOwner();
			if (closed || snapshot.generation() != requested.generation() || pending.get(index) != read)
				return;
			pending.remove(index);
			if (index >= read.validEnd) {
				if (index < size())
					request(index, false);
				return;
			}
			if (result.error() != null && snapshot != requested) {
				request(index, prefetch);
				return;
			}
			int end = index + 1;
			if (result.error() != null) {
				currentBatch = null;
				batchBytes = 0;
				cacheLine(index, LogLine.readError(index + 1, java.util.Objects.toString(
						result.error().getMessage(), result.error().getClass().getSimpleName())));
			} else {
				LogFileModel.Batch batch = result.batch();
				currentBatch = null;
				batchBytes = 0;
				long weight = batchWeight(batch);
				if (weight <= byteBudget && batch.endLine() <= read.validEnd) {
					currentBatch = batch;
					batchBytes = weight;
					trimCache();
				}
				end = Math.min(batch.endLine(), read.validEnd);
				for (int i = batch.startLine(); i < end; i++) {
					if (i != index && !cache.containsKey(i))
						cacheLine(i, new LogLine(i + 1, batch.getLines().get(i - batch.startLine())));
				}
				cacheLine(index, new LogLine(index + 1, batch.getLines().getFirst()));
				// Never loop forever loading a row that cannot fit this cache.
				if (currentBatch == null && !cache.containsKey(index))
					cacheLine(index, LogLine.readError(index + 1, "Line exceeds this view's cache budget"));
			}
			beginChange();
			try {
				for (int i = index; i < end; i++)
					nextUpdate(i);
			} finally {
				endChange();
			}
			// At most one adjacent batch is prefetched; prefetch never chains.
			if (!prefetch && result.error() == null && end < size() && !cache.containsKey(end))
				request(end, true);
		})) {
			pending.remove(index);
			if (!prefetch && Platform.isFxApplicationThread()) {
				retryIndex = index;
				if (retry == null) {
					retry = new javafx.animation.PauseTransition(javafx.util.Duration.millis(RETRY_DELAY_MILLIS));
					retry.setOnFinished(_ -> request(retryIndex, false));
				}
				if (retry.getStatus() != javafx.animation.Animation.Status.RUNNING)
					retry.playFromStart();
			}
		}
	}

	public void retryUnavailable() {
		checkOwner();
		var indices = cache.entrySet().stream().filter(entry -> !entry.getValue().isAvailable())
				.map(Map.Entry::getKey).toList();
		beginChange();
		try {
			for (int index : indices) {
				removeCachedLine(index);
				nextUpdate(index);
			}
		} finally {
			endChange();
		}
	}

	private void checkOwner() {
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("Access log lists on their owning thread");
	}

	public void close() {
		checkOwner();
		closed = true;
		if (retry != null)
			retry.stop();
		loader.cancel(this);
		pending.clear();
		cache.clear();
		cachedBytes = 0;
		currentBatch = null;
		batchBytes = 0;
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
		return ENTRY_OVERHEAD_BYTES + (line.content() == null ? 0 : stringWeight(line.content()))
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
		if (closed)
			return;
		if (retry != null)
			retry.stop();
		LogFileModel.Snapshot previous = snapshot;
		int oldSize = size();
		boolean reset = previous.generation() != update.generation();
		if (reset) {
			loader.cancel(this);
			pending.clear();
		} else {
			for (PendingRead read : pending.values())
				read.validEnd = Math.min(read.validEnd, Math.max(0, oldSize - 1));
		}
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
						return LogLine.readError(index + 1, "Historical row is no longer cached");
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
