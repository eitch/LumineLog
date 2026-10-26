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
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ListChangeListener;
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
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

public class LogListView extends ListView<LogLine> {

	private static final int TAIL_LAYOUT_PULSES = 2;
	private static final double DRAG_EDGE_PIXELS = 16;
	private static final long DRAG_SCROLL_NANOS = 60_000_000L;
	private final BooleanProperty following = new SimpleBooleanProperty(true);
	private final List<Observable> eligibilityObservables = new ArrayList<>();
	private final InvalidationListener eligibilityChanged = _ -> observeEligibility();
	private boolean disposed;
	private boolean animationRunning;
	private boolean pressed;
	private boolean dragging;
	private boolean scrollbarGesture;
	private int anchor = -1;
	private int endpoint = -1;
	private double pointerY;
	private int tailPulses;
	private long animationCallbacks;
	private long lastDragScroll;
	private int pendingSelection = -1;
	private final ListChangeListener<LogLine> loadedLines = change -> {
		while (change.next()) {
			if (change.wasReplaced() || change.wasRemoved())
				pendingSelection = -1;
		}
		selectPendingLine();
		updateAnimationState();
	};
	private final AnimationTimer animation = new AnimationTimer() {
		@Override
		public void handle(long now) {
			animationCallbacks++;
			if (!canAnimate()) {
				updateAnimationState();
				return;
			}
			if (hasRunnableDrag() && now - lastDragScroll > DRAG_SCROLL_NANOS) {
				lastDragScroll = now;
				autoScroll();
			}
			if (hasRunnableTail()) {
				applyCss();
				layout();
				if (!getItems().isEmpty())
					scrollTo(getItems().size() - 1);
				tailPulses--;
			}
			updateAnimationState();
		}
	};

	public LogListView() {
		setSelectionModel(new AvailableLineSelectionModel(this));
		getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		getItems().addListener(loadedLines);
		itemsProperty().addListener((_, oldItems, newItems) -> {
			pendingSelection = -1;
			if (oldItems != null)
				oldItems.removeListener(loadedLines);
			if (newItems != null)
				newItems.addListener(loadedLines);
			updateAnimationState();
		});
		widthProperty().addListener((_, _, _) -> requestTail());
		heightProperty().addListener((_, _, _) -> requestTail());
		following.addListener((_, _, enabled) -> {
			if (enabled)
				requestTail();
			else
				updateAnimationState();
		});
		sceneProperty().addListener((_, _, _) -> observeEligibility());
		observeEligibility();
		addEventFilter(ScrollEvent.SCROLL, event -> {
			pendingSelection = -1;
			if (event.getDeltaY() > 0)
				following.set(false);
			else if (event.getDeltaY() < 0)
				Platform.runLater(this::resumeAtBottom);
		});
		addEventFilter(KeyEvent.KEY_PRESSED, event -> {
			pendingSelection = -1;
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
		if (!disposed && following.get())
			tailPulses = TAIL_LAYOUT_PULSES;
		updateAnimationState();
	}

	private void observeEligibility() {
		for (Observable observable : eligibilityObservables)
			observable.removeListener(eligibilityChanged);
		eligibilityObservables.clear();
		if (disposed)
			return;
		for (Node node = this; node != null; node = node.getParent()) {
			observe(node.visibleProperty());
			observe(node.parentProperty());
		}
		if (getScene() != null) {
			observe(getScene().windowProperty());
			if (getScene().getWindow() != null) {
				observe(getScene().getWindow().showingProperty());
				if (getScene().getWindow() instanceof Stage stage)
					observe(stage.iconifiedProperty());
			}
		}
		requestTail();
	}

	private void observe(Observable observable) {
		eligibilityObservables.add(observable);
		observable.addListener(eligibilityChanged);
	}

	private boolean canAnimate() {
		if (disposed || getHeight() <= 0 || getScene() == null || getScene().getWindow() == null
				|| !getScene().getWindow().isShowing()
				|| getScene().getWindow() instanceof Stage stage && stage.isIconified())
			return false;
		for (Node node = this; node != null; node = node.getParent()) {
			if (!node.isVisible())
				return false;
		}
		return true;
	}

	private boolean hasRunnableTail() {
		return tailPulses > 0 && following.get() && !pressed && !getItems().isEmpty();
	}

	private boolean hasRunnableDrag() {
		return dragging && !getItems().isEmpty()
				&& (pointerY < DRAG_EDGE_PIXELS && endpoint > 0
				|| pointerY > getHeight() - DRAG_EDGE_PIXELS && endpoint < getItems().size() - 1);
	}

	private void updateAnimationState() {
		boolean runnable = canAnimate() && (hasRunnableDrag() || hasRunnableTail());
		if (runnable == animationRunning)
			return;
		animationRunning = runnable;
		if (runnable)
			animation.start();
		else
			animation.stop();
	}

	public void dispose() {
		disposed = true;
		tailPulses = 0;
		pendingSelection = -1;
		cancelSelectionGesture();
		observeEligibility();
	}

	public void cancelSelectionGesture() {
		pressed = false;
		dragging = false;
		anchor = -1;
		endpoint = -1;
		updateAnimationState();
	}

	private void resumeAtBottom() {
		if (disposed || dragging)
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
		pendingSelection = -1;
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
			updateAnimationState();
		}
	}

	public void navigateTo(int index) {
		if (index < 0 || index >= getItems().size())
			return;
		following.set(false);
		pendingSelection = index;
		scrollTo(index);
		selectPendingLine();
	}

	private void selectPendingLine() {
		if (pendingSelection < 0 || pendingSelection >= getItems().size())
			return;
		LogLine line = getItems().get(pendingSelection);
		if (!line.isLoading()) {
			int index = pendingSelection;
			pendingSelection = -1;
			if (line.isAvailable())
				getSelectionModel().clearAndSelect(index);
		}
	}

	private void mouseDragged(MouseEvent event) {
		if (!pressed || !event.isPrimaryButtonDown() || anchor < 0)
			return;
		dragging = true;
		following.set(false);
		pointerY = sceneToLocal(event.getSceneX(), event.getSceneY()).getY();
		selectTo(indexAt(pointerY));
		updateAnimationState();
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
		if (pointerY >= DRAG_EDGE_PIXELS && pointerY <= getHeight() - DRAG_EDGE_PIXELS)
			return;
		int edge = indexAt(pointerY < DRAG_EDGE_PIXELS ? 0 : getHeight());
		if (edge < 0)
			return;
		int next = Math.clamp(edge + (pointerY < DRAG_EDGE_PIXELS ? -1 : 1), 0, getItems().size() - 1);
		scrollTo(next);
		selectTo(next);
	}
}