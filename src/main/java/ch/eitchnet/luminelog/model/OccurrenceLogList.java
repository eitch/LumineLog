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
import javafx.collections.ObservableListBase;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Identifier-backed results; decoded content is owned only by the bounded source cache. */
public final class OccurrenceLogList extends ObservableListBase<LogLine> implements AutoCloseable, NonLoadingAccess {

	private final OccurrenceIndex index;
	private final VirtualLogList source;
	private final VirtualLogList live;
	public static final int MAX_CACHED_PAGES = 4;
	public static final int MAX_PENDING_PAGES = 2;
	private final OccurrencePageService pageLoader;
	private final boolean paged;
	private final LinkedHashMap<Integer, int[]> pages = new LinkedHashMap<>(8, 0.75f, true);
	private final LinkedHashSet<Integer> pendingPages = new LinkedHashSet<>();
	private boolean loadingSuppressed;
	private String pageError;
	private javafx.animation.PauseTransition retry;
	private final long generation;
	private final Thread owner = Thread.currentThread();
	private final ListChangeListener<LogLine> liveListener = _ -> checkGeneration();
	private boolean unavailable;
	private boolean closed;

	public OccurrenceLogList(LogFileModel model, VirtualLogList live, LogFileModel.Snapshot snapshot,
			OccurrenceIndex index, LineLoadingService loader) {
		this(model, live, snapshot, index, loader, null);
	}

	public OccurrenceLogList(LogFileModel model, VirtualLogList live, LogFileModel.Snapshot snapshot,
			OccurrenceIndex index, LineLoadingService loader, OccurrencePageService pageLoader) {
		this.index = index;
		this.live = live;
		this.pageLoader = pageLoader;
		this.paged = index instanceof SpillableOccurrenceIndex spill && spill.isSpilled();
		if (paged)
			java.util.Objects.requireNonNull(pageLoader, "Spilled results require a page loader");
		this.generation = snapshot.generation();
		this.source = new VirtualLogList(model, snapshot, VirtualLogList.DEFAULT_CACHE_BYTES,
				VirtualLogList.DEFAULT_CACHE_ENTRIES, loader, line -> {
					if (paged)
						return true; // VirtualLogList retains only explicitly requested/deferred rows.
					int position = index.lowerBound(line);
					return position < index.size() && index.lineIndex(position) == line;
				});
		source.addListener((ListChangeListener<LogLine>) change -> {
			if (closed || unavailable)
				return;
			beginChange();
			try {
				while (change.next()) {
					if (paged) {
						for (var entry : pages.entrySet()) {
							int from = bound(entry.getValue(), change.getFrom());
							int to = bound(entry.getValue(), change.getTo());
							for (int i = from; i < to; i++)
								nextUpdate(entry.getKey() * SpillableOccurrenceIndex.PAGE_SIZE + i);
						}
					} else {
						int from = index.lowerBound(change.getFrom());
						int to = index.lowerBound(change.getTo());
						for (int i = from; i < to; i++)
							nextUpdate(i);
					}
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
		cancelPages();
		int count = size();
		// Bypass ListChangeBuilder: nextReplace would copy every removed row.
		fireChange(new ListChangeListener.Change<LogLine>(this) {
			private boolean delivered;
			private final java.util.List<LogLine> removed = new java.util.AbstractList<>() {
				@Override
				public LogLine get(int position) {
					return LogLine.readError(paged ? 0 : index.lineIndex(position) + 1, "Historical occurrence unavailable");
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
		java.util.Objects.checkIndex(position, size());
		if (unavailable || closed)
			return LogLine.readError(paged ? 0 : index.lineIndex(position) + 1,
					"Occurrence snapshot unavailable; search again");
		if (pageError != null)
			return LogLine.readError(0, pageError);
		int original;
		if (paged) {
			int page = position / SpillableOccurrenceIndex.PAGE_SIZE;
			int[] identifiers = pages.get(page);
			if (identifiers == null) {
				if (!loadingSuppressed)
					requestPage(page);
				return LogLine.loading(0);
			}
			original = identifiers[position % SpillableOccurrenceIndex.PAGE_SIZE];
		} else {
			original = index.lineIndex(position);
		}
		return source.get(original);
	}

	private static int bound(int[] identifiers, int line) {
		int found = Arrays.binarySearch(identifiers, line);
		return found < 0 ? -found - 1 : found;
	}

	private void requestPage(int page) {
		if (pendingPages.contains(page))
			return;
		if (pendingPages.size() >= MAX_PENDING_PAGES) {
			pageLoader.cancel(this);
			pendingPages.clear();
		}
		pendingPages.add(page);
		if (!pageLoader.request(this, index, page, result -> {
			checkOwner();
			if (closed || unavailable || !pendingPages.remove(page))
				return;
			if (result.error() != null) {
				pageError = "Occurrence identifiers unavailable: " + result.error().getMessage()
						+ ". Retry unavailable lines or search again.";
			} else {
				pages.put(page, result.identifiers());
				while (pages.size() > MAX_CACHED_PAGES)
					pages.remove(pages.keySet().iterator().next());
			}
			updatePage(page);
		})) {
			pendingPages.remove(page);
			if (javafx.application.Platform.isFxApplicationThread()) {
				if (retry == null)
					retry = new javafx.animation.PauseTransition(
							javafx.util.Duration.millis(VirtualLogList.RETRY_DELAY_MILLIS));
				retry.setOnFinished(_ -> {
					if (!closed && !unavailable && pageError == null && !pages.containsKey(page))
						requestPage(page);
				});
				retry.playFromStart();
			}
		}
	}

	private void updatePage(int page) {
		beginChange();
		try {
			int from = page * SpillableOccurrenceIndex.PAGE_SIZE;
			int to = (int) Math.min(size(), (long) from + SpillableOccurrenceIndex.PAGE_SIZE);
			for (int i = from; i < to; i++)
				nextUpdate(i);
		} finally {
			endChange();
		}
	}

	public long accountedPageBytes() {
		checkOwner();
		return pages.values().stream().mapToLong(page -> (long) page.length * Integer.BYTES).sum();
	}

	private void cancelPages() {
		if (pageLoader != null)
			pageLoader.cancel(this);
		pendingPages.clear();
		if (retry != null)
			retry.stop();
	}

	@Override
	public int size() {
		return index.size();
	}

	@Override
	public void withoutLoading(Runnable action) {
		checkOwner();
		boolean previous = loadingSuppressed;
		loadingSuppressed = true;
		try {
			source.withoutLoading(action);
		} finally {
			loadingSuppressed = previous;
		}
	}

	public void retryUnavailable() {
		checkOwner();
		if (!closed && !unavailable) {
			pageError = null;
			source.retryUnavailable();
			// Listeners/rendering can request the failed page again without synchronous I/O.
			if (paged)
				fireChange(new ListChangeListener.Change<LogLine>(this) {
					private boolean delivered;
					@Override public boolean next() { if (delivered) return false; delivered = true; return true; }
					@Override public void reset() { delivered = false; }
					@Override public int getFrom() { return 0; }
					@Override public int getTo() { return size(); }
					@Override public java.util.List<LogLine> getRemoved() { return java.util.List.of(); }
					@Override public boolean wasUpdated() { return true; }
					@Override protected int[] getPermutation() { return new int[0]; }
				});
		}
	}

	@Override
	public void close() {
		checkOwner();
		if (closed)
			return;
		closed = true;
		live.removeListener(liveListener);
		source.close();
		cancelPages();
		pages.clear();
		index.close();
	}
}