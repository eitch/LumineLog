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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VirtualLogList extends ObservableListBase<LogLine> {

	private final LogFileModel logModel;
	private final Map<Integer, LogLine> cache;
	private static final int CACHE_SIZE = 1000;

	private LogFileModel.Batch currentBatch;

	public VirtualLogList(LogFileModel logModel) {
		this.logModel = logModel;
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
				currentBatch = logModel.readBatch(index);
			} catch (IOException e) {
				return new LogLine(index + 1, "ERROR READING BATCH AT " + index + ": " + e.getMessage());
			}
		}

		List<String> lines = currentBatch.getLines(logModel);
		for (int i = 0; i < lines.size(); i++) {
			int lineIndex = currentBatch.startLine() + i;
			cache.putIfAbsent(lineIndex, new LogLine(lineIndex + 1, lines.get(i)));
		}

		return cache.get(index);
	}

	@Override
	public int size() {
		return logModel.getLineCount();
	}

	public void fireSizeChanged(int oldSize, int newSize) {
		currentBatch = null;
		if (newSize > oldSize) {
			// Remove the previous last entry from cache if it was an empty line due to trailing newline
			// because now it might have content.
			if (oldSize > 0)
				cache.remove(oldSize - 1);
			beginChange();
			nextAdd(oldSize, newSize);
			endChange();
		} else if (newSize < oldSize) {
			// Probably truncated
			cache.clear();
			beginChange();
			nextReplace(0, oldSize, java.util.Collections.emptyList());
			nextAdd(0, newSize);
			endChange();
		} else {
			// Size unchanged, but content may have changed (e.g. truncate + rewrite).
			cache.clear();
		}
	}
}
