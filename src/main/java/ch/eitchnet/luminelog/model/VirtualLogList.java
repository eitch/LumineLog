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

	private final LogFileModel logModel;
	private final Thread owner = Thread.currentThread();
	private LogFileModel.Snapshot snapshot;
	private final Map<Integer, LogLine> cache;
	private static final int CACHE_SIZE = 1000;

	private LogFileModel.Batch currentBatch;

	public VirtualLogList(LogFileModel logModel) {
		this.logModel = logModel;
		this.snapshot = logModel.snapshot();
		this.cache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Integer, LogLine> eldest) {
				return size() > CACHE_SIZE;
			}
		};
	}

	@Override
	public LogLine get(int index) {
		if (index < 0 || index >= size())
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());

		LogLine cached = cache.get(index);
		if (cached != null)
			return cached;

		if (currentBatch == null || index < currentBatch.startLine() || index >= currentBatch.endLine()) {
			try {
				currentBatch = snapshot.readBatch(index);
			} catch (IOException e) {
				LogLine error = LogLine.readError(index + 1, e.getMessage());
				if (e instanceof LogFileModel.OversizedLineException)
					cache.put(index, error);
				return error;
			}
		}

		List<String> lines = currentBatch.getLines();
		for (int i = 0; i < lines.size(); i++) {
			int lineIndex = currentBatch.startLine() + i;
			cache.putIfAbsent(lineIndex, new LogLine(lineIndex + 1, lines.get(i)));
		}

		return cache.get(index);
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
		beginChange();
		try {
			if (reset) {
				cache.clear();
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
					cache.remove(oldSize - 1);
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
