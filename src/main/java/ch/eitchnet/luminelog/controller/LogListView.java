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
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

public class LogListView extends ListView<LogLine> {

	private final BooleanProperty following = new SimpleBooleanProperty(true);
	private boolean pressed;
	private boolean dragging;
	private boolean scrollbarGesture;
	private int anchor = -1;
	private int endpoint = -1;
	private double pointerY;
	private int tailPulses;
	private long lastDragScroll;
	private final AnimationTimer animation = new AnimationTimer() {
		@Override
		public void handle(long now) {
			if (dragging && now - lastDragScroll > 60_000_000L) {
				lastDragScroll = now;
				autoScroll();
			}
			if (tailPulses > 0 && following.get() && !pressed && getHeight() > 0 && isVisible()) {
				applyCss();
				layout();
				if (!getItems().isEmpty())
					scrollTo(getItems().size() - 1);
				tailPulses--;
			}
			if (!dragging && (tailPulses == 0 || !following.get()))
				stop();
		}
	};

	public LogListView() {
		getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		widthProperty().addListener((_, _, _) -> requestTail());
		heightProperty().addListener((_, _, _) -> requestTail());
		following.addListener((_, _, enabled) -> {
			if (enabled)
				requestTail();
		});
		sceneProperty().addListener((_, _, scene) -> {
			if (scene == null)
				animation.stop();
			else
				requestTail();
		});
		addEventFilter(ScrollEvent.SCROLL, event -> {
			if (event.getDeltaY() > 0)
				following.set(false);
			else if (event.getDeltaY() < 0)
				Platform.runLater(this::resumeAtBottom);
		});
		addEventFilter(KeyEvent.KEY_PRESSED, event -> {
			KeyCode code = event.getCode();
			if (code == KeyCode.UP || code == KeyCode.PAGE_UP || code == KeyCode.HOME)
				following.set(false);
			else if (code == KeyCode.END)
				following.set(true);
			else if (code == KeyCode.DOWN || code == KeyCode.PAGE_DOWN)
				Platform.runLater(this::resumeAtBottom);
		});
		addEventFilter(MouseEvent.MOUSE_PRESSED, this::mousePressed);
		addEventFilter(MouseEvent.MOUSE_DRAGGED, this::mouseDragged);
		addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
			if (dragging)
				event.consume();
			cancelSelectionGesture();
			if (scrollbarGesture)
				Platform.runLater(this::resumeAtBottom);
			scrollbarGesture = false;
			requestTail();
		});
	}

	public BooleanProperty followingProperty() {
		return following;
	}

	public void requestTail() {
		if (following.get()) {
			tailPulses = 2;
			if (getScene() != null)
				animation.start();
		}
	}

	public void dispose() {
		cancelSelectionGesture();
		animation.stop();
	}

	public void cancelSelectionGesture() {
		pressed = false;
		dragging = false;
		anchor = -1;
		endpoint = -1;
	}

	private void resumeAtBottom() {
		if (dragging)
			return;
		applyCss();
		layout();
		Node node = lookup(".scroll-bar:vertical");
		if (node instanceof ScrollBar bar && (!bar.isVisible() || bar.getValue() >= bar.getMax() - 0.0001))
			following.set(true);
	}

	private ScrollBar scrollBar(Node node) {
		while (node != null && node != this) {
			if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL)
				return bar;
			node = node.getParent();
		}
		return null;
	}

	private void mousePressed(MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY)
			return;
		Node node = event.getPickResult().getIntersectedNode();
		if (scrollBar(node) != null) {
			scrollbarGesture = true;
			following.set(false);
			return;
		}
		while (node != null && !(node instanceof ListCell<?>))
			node = node.getParent();
		if (node instanceof ListCell<?> cell && !cell.isEmpty() && !event.isShortcutDown()) {
			pressed = true;
			anchor = event.isShiftDown() && getFocusModel().getFocusedIndex() >= 0
					? getFocusModel().getFocusedIndex() : cell.getIndex();
			endpoint = -1;
		}
	}

	private void mouseDragged(MouseEvent event) {
		if (!pressed || !event.isPrimaryButtonDown() || anchor < 0)
			return;
		dragging = true;
		following.set(false);
		pointerY = sceneToLocal(event.getSceneX(), event.getSceneY()).getY();
		selectTo(indexAt(pointerY));
		animation.start();
		event.consume();
	}

	private int indexAt(double y) {
		int index = -1;
		double distance = Double.MAX_VALUE;
		for (Node node : lookupAll(".list-cell")) {
			if (!(node instanceof ListCell<?> cell) || cell.isEmpty())
				continue;
			Bounds bounds = sceneToLocal(cell.localToScene(cell.getBoundsInLocal()));
			if (bounds.getMaxY() < 0 || bounds.getMinY() > getHeight())
				continue;
			double d = Math.max(bounds.getMinY() - y, Math.max(0, y - bounds.getMaxY()));
			if (d < distance) {
				distance = d;
				index = cell.getIndex();
			}
		}
		return index;
	}

	private void selectTo(int index) {
		if (index < 0 || index >= getItems().size() || anchor >= getItems().size() || index == endpoint)
			return;
		int low = Math.min(anchor, index);
		int high = Math.max(anchor, index);
		if (endpoint < 0) {
			getSelectionModel().clearSelection();
			getSelectionModel().selectRange(low, high + 1);
		} else {
			int oldLow = Math.min(anchor, endpoint);
			int oldHigh = Math.max(anchor, endpoint);
			for (int i = oldLow; i < low; i++)
				getSelectionModel().clearSelection(i);
			for (int i = high + 1; i <= oldHigh; i++)
				getSelectionModel().clearSelection(i);
			if (low < oldLow)
				getSelectionModel().selectRange(low, oldLow);
			if (high > oldHigh)
				getSelectionModel().selectRange(oldHigh + 1, high + 1);
		}
		endpoint = index;
		getFocusModel().focus(index);
	}

	private void autoScroll() {
		if (pointerY >= 16 && pointerY <= getHeight() - 16)
			return;
		int edge = indexAt(pointerY < 16 ? 0 : getHeight());
		if (edge < 0)
			return;
		int next = Math.clamp(edge + (pointerY < 16 ? -1 : 1), 0, getItems().size() - 1);
		scrollTo(next);
		selectTo(next);
	}
}