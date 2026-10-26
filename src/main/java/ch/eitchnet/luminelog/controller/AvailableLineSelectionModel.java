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
package ch.eitchnet.luminelog.controller;

import ch.eitchnet.luminelog.model.LogLine;
import javafx.collections.ObservableList;
import javafx.scene.control.ListView;
import javafx.scene.control.MultipleSelectionModel;

/** Keeps JavaFX's index/notification handling, but never selects presentation states. */
final class AvailableLineSelectionModel extends MultipleSelectionModel<LogLine> {

	private final ListView<LogLine> view;
	private final MultipleSelectionModel<LogLine> delegate;

	AvailableLineSelectionModel(ListView<LogLine> view) {
		this.view = view;
		this.delegate = view.getSelectionModel();
		delegate.selectionModeProperty().bind(selectionModeProperty());
		delegate.selectedIndexProperty().addListener((_, _, index) -> setSelectedIndex(index.intValue()));
		delegate.selectedItemProperty().addListener((_, _, item) -> {
			setSelectedItem(item);
			if (item != null && !item.isAvailable())
				delegate.clearSelection();
		});
	}

	private boolean available(int index) {
		return index >= 0 && index < view.getItems().size() && view.getItems().get(index).isAvailable();
	}

	@Override public ObservableList<Integer> getSelectedIndices() { return delegate.getSelectedIndices(); }
	@Override public ObservableList<LogLine> getSelectedItems() { return delegate.getSelectedItems(); }
	@Override public void clearSelection() { delegate.clearSelection(); }
	@Override public void clearSelection(int index) { delegate.clearSelection(index); }
	@Override public boolean isSelected(int index) { return delegate.isSelected(index); }
	@Override public boolean isEmpty() { return delegate.isEmpty(); }
	@Override public void select(int index) {
		if (available(index))
			delegate.select(index);
	}
	@Override public void clearAndSelect(int index) {
		if (available(index))
			delegate.clearAndSelect(index);
	}
	@Override public void select(LogLine item) {
		if (item != null && item.isAvailable())
			select(view.getItems().indexOf(item));
	}
	@Override public void selectIndices(int index, int... indices) {
		select(index);
		for (int next : indices)
			select(next);
	}
	@Override public void selectRange(int start, int end) {
		int direction = start < end ? 1 : -1;
		int runStart = start;
		for (int index = start; index != end; index += direction) {
			if (!available(index)) {
				if (runStart != index)
					delegate.selectRange(runStart, index);
				runStart = index + direction;
			}
		}
		if (runStart != end)
			delegate.selectRange(runStart, end);
	}
	@Override public void selectAll() { selectRange(0, view.getItems().size()); }
	@Override public void selectFirst() { select(0); }
	@Override public void selectLast() { select(view.getItems().size() - 1); }
	@Override public void selectPrevious() { select(getSelectedIndex() - 1); }
	@Override public void selectNext() { select(getSelectedIndex() + 1); }
}