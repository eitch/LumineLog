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
import javafx.collections.ObservableListBase;

/** Identifier-backed results; decoded content is owned only by the bounded source cache. */
public final class OccurrenceLogList extends ObservableListBase<LogLine> implements AutoCloseable, NonLoadingAccess {

	private final OccurrenceIndex index;
	private final VirtualLogList source;
	private final VirtualLogList live;
	private final long generation;
	private final Thread owner = Thread.currentThread();
	private final ListChangeListener<LogLine> liveListener = _ -> checkGeneration();
	private boolean unavailable;
	private boolean closed;

	public OccurrenceLogList(LogFileModel model, VirtualLogList live, LogFileModel.Snapshot snapshot,
			OccurrenceIndex index, LineLoadingService loader) {
		this.index = index;
		this.live = live;
		this.generation = snapshot.generation();
		this.source = new VirtualLogList(model, snapshot, VirtualLogList.DEFAULT_CACHE_BYTES,
				VirtualLogList.DEFAULT_CACHE_ENTRIES, loader, line -> {
					int position = index.lowerBound(line);
					return position < index.size() && index.lineIndex(position) == line;
				});
		source.addListener((ListChangeListener<LogLine>) change -> {
			if (closed || unavailable)
				return;
			beginChange();
			try {
				while (change.next()) {
					int from = index.lowerBound(change.getFrom());
					int to = index.lowerBound(change.getTo());
					for (int i = from; i < to; i++)
						nextUpdate(i);
				}
			} finally {
				endChange();
			}
		});
		live.addListener(liveListener);
		checkGeneration();
	}

	private void checkOwner() {
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("Access occurrence lists on their owning thread");
	}

	private void checkGeneration() {
		checkOwner();
		if (closed || unavailable || live.snapshot().generation() == generation)
			return;
		unavailable = true;
		source.close();
		int count = size();
		// Bypass ListChangeBuilder: nextReplace would copy every removed row.
		fireChange(new ListChangeListener.Change<LogLine>(this) {
			private boolean delivered;
			private final java.util.List<LogLine> removed = new java.util.AbstractList<>() {
				@Override
				public LogLine get(int position) {
					return LogLine.readError(index.lineIndex(position) + 1, "Historical occurrence unavailable");
				}

				@Override
				public int size() {
					return count;
				}
			};

			@Override
			public boolean next() {
				if (delivered)
					return false;
				delivered = true;
				return true;
			}

			@Override
			public void reset() { delivered = false; }
			@Override
			public int getFrom() { return 0; }
			@Override
			public int getTo() { return count; }
			@Override
			public java.util.List<LogLine> getRemoved() { return removed; }
			@Override
			protected int[] getPermutation() { return new int[0]; }
		});
	}

	@Override
	public LogLine get(int position) {
		checkOwner();
		int original = index.lineIndex(position);
		if (unavailable || closed)
			return LogLine.readError(original + 1, "Occurrence snapshot unavailable; search again");
		return source.get(original);
	}

	@Override
	public int size() {
		return index.size();
	}

	@Override
	public void withoutLoading(Runnable action) {
		checkOwner();
		source.withoutLoading(action);
	}

	public void retryUnavailable() {
		checkOwner();
		if (!closed && !unavailable)
			source.retryUnavailable();
	}

	@Override
	public void close() {
		checkOwner();
		if (closed)
			return;
		closed = true;
		live.removeListener(liveListener);
		source.close();
		index.close();
	}
}