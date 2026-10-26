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

import ch.eitchnet.luminelog.model.HighlightRule;
import ch.eitchnet.luminelog.model.LogFileModel;
import ch.eitchnet.luminelog.model.LogLine;
import ch.eitchnet.luminelog.model.VirtualLogList;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class LogListViewTest {

	private Stage stage;
	private LogListView view;
	@TempDir
	Path tempDir;

	@BeforeAll
	static void startToolkit() throws Exception {
		CompletableFuture<Void> ready = new CompletableFuture<>();
		Platform.startup(() -> {
			Platform.setImplicitExit(false);
			ready.complete(null);
		});
		ready.get(10, TimeUnit.SECONDS);
	}

	private static void fx(Runnable action) throws Exception {
		FutureTask<Void> task = new FutureTask<>(action, null);
		Platform.runLater(task);
		task.get(10, TimeUnit.SECONDS);
	}

	private static void pulses() throws Exception {
		CompletableFuture<Void> ready = new CompletableFuture<>();
		fx(() -> {
			PauseTransition delay = new PauseTransition(Duration.millis(150));
			delay.setOnFinished(_ -> ready.complete(null));
			delay.play();
		});
		ready.get(10, TimeUnit.SECONDS);
	}

	private void show() throws Exception {
		fx(() -> {
			view = new LogListView();
			view.setFixedCellSize(24);
			view.setItems(FXCollections.observableArrayList(IntStream.range(0, 100)
					.mapToObj(i -> new LogLine(i + 1, "line " + i)).toList()));
			stage = new Stage();
			stage.setScene(new Scene(new StackPane(view), 500, 300));
			stage.show();
			view.requestTail();
		});
		pulses();
	}

	@AfterEach
	void close() throws Exception {
		fx(() -> {
			if (view != null)
				view.dispose();
			if (stage != null)
				stage.close();
		});
	}

	@Test
	void layoutAndAppendsDoNotDisableFollowButUserNavigationDoes() throws Exception {
		show();
		fx(() -> {
			ScrollBar bar = (ScrollBar) view.lookup(".scroll-bar:vertical");
			bar.setValue(bar.getMax() / 2);
			assertTrue(view.followingProperty().get());
			view.getItems().add(new LogLine(101, "appended"));
			view.requestTail();
		});
		pulses();
		fx(() -> {
			ScrollBar bar = (ScrollBar) view.lookup(".scroll-bar:vertical");
			assertEquals(bar.getMax(), bar.getValue(), 0.0001);
			view.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.PAGE_UP,
					false, false, false, false));
			assertFalse(view.followingProperty().get());
			view.getItems().add(new LogLine(102, "another"));
			view.requestTail();
		});
		pulses();
		fx(() -> assertFalse(view.followingProperty().get()));
	}

	@Test
	void draggingKeepsAStableRangeAndIgnoresRepeatedEndpoints() throws Exception {
		show();
		fx(() -> {
			view.followingProperty().set(false);
			view.scrollTo(0);
		});
		pulses();
		fx(() -> {
			ListCell<?> first = cell(1);
			ListCell<?> last = cell(4);
			mouse(first, MouseEvent.MOUSE_PRESSED);
			mouse(last, MouseEvent.MOUSE_DRAGGED);
			assertEquals(java.util.List.of(1, 2, 3, 4), view.getSelectionModel().getSelectedIndices());
			AtomicInteger changes = new AtomicInteger();
			view.getSelectionModel().getSelectedIndices().addListener((ListChangeListener<Integer>) _ -> changes.incrementAndGet());
			mouse(last, MouseEvent.MOUSE_DRAGGED);
			assertEquals(0, changes.get());
			mouse(cell(2), MouseEvent.MOUSE_DRAGGED);
			assertEquals(java.util.List.of(1, 2), view.getSelectionModel().getSelectedIndices());
			assertFalse(view.followingProperty().get());
			mouse(cell(2), MouseEvent.MOUSE_RELEASED);
			assertEquals(java.util.List.of(1, 2), view.getSelectionModel().getSelectedIndices());
		});
	}

	@Test
	void followStateIsIndependentForEachView() throws Exception {
		show();
		fx(() -> {
			LogListView other = new LogListView();
			view.followingProperty().set(false);
			assertTrue(other.followingProperty().get());
			other.dispose();
		});
	}

	@Test
	void edgeDraggingScrollsAndAppendingDoesNotMoveSelection() throws Exception {
		show();
		fx(() -> {
			view.followingProperty().set(false);
			view.scrollTo(0);
		});
		pulses();
		fx(() -> {
			mouse(cell(1), MouseEvent.MOUSE_PRESSED);
			ListCell<?> bottom = view.lookupAll(".list-cell").stream()
					.filter(node -> node instanceof ListCell<?> cell && !cell.isEmpty()
							&& cell.localToScene(0, 12).getY() < view.getHeight())
					.map(node -> (ListCell<?>) node)
					.max(java.util.Comparator.comparingInt(ListCell::getIndex)).orElseThrow();
			var point = view.localToScene(20, view.getHeight() + 20);
			bottom.fireEvent(new MouseEvent(MouseEvent.MOUSE_DRAGGED, point.getX(), point.getY(),
					point.getX(), point.getY(), MouseButton.PRIMARY, 1, false, false, false, false,
					true, false, false, false, false, false, new PickResult(bottom, point.getX(), point.getY())));
		});
		pulses();
		pulses();
		fx(() -> {
			assertTrue(view.getSelectionModel().getSelectedIndices().getLast() > 12);
			var selected = java.util.List.copyOf(view.getSelectionModel().getSelectedIndices());
			view.getItems().add(new LogLine(101, "new"));
			view.requestTail();
			assertEquals(selected, view.getSelectionModel().getSelectedIndices());
			view.cancelSelectionGesture();
		});
	}

	@Test
	void fontSizeControlsRowAndLineNumberGeometryWithoutWrapping() throws Exception {
		show();
		fx(() -> {
			try {
				MainController controller = new MainController();
				setField(controller, "fontSizeSpinner", new javafx.scene.control.Spinner<Integer>(8, 32, 32));
				var configure = MainController.class.getDeclaredMethod("configureRowHeight", javafx.scene.control.ListView.class);
				configure.setAccessible(true);
				configure.invoke(controller, view);
				var factory = MainController.class.getDeclaredMethod("getHighlightingCellCallback");
				factory.setAccessible(true);
				@SuppressWarnings("unchecked")
				var callback = (javafx.util.Callback<javafx.scene.control.ListView<LogLine>, ListCell<LogLine>>) factory.invoke(controller);
				view.setCellFactory(callback);
				view.setItems(FXCollections.observableArrayList(new LogLine(1, "long line ".repeat(100))));
				view.applyCss();
				view.layout();
				ListCell<?> cell = cell(0);
				assertTrue(view.getFixedCellSize() > 32);
				assertEquals(view.getFixedCellSize(), cell.getHeight(), 0.1);
				var flow = (javafx.scene.text.TextFlow) cell.getGraphic();
				var number = (javafx.scene.text.Text) flow.getChildren().getFirst();
				assertEquals(32, number.getFont().getSize());
				assertTrue(flow.getWidth() > view.getWidth());
				assertTrue(flow.getHeight() <= view.getFixedCellSize());
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	@Test
	void highlightCountsRevisitPartialFinalLinesAndResetAfterRewrite() throws Exception {
		Path file = tempDir.resolve("counts.log");
		Files.writeString(file, "ERR");
		LogFileModel model = new LogFileModel(file);
		MainController[] controller = new MainController[1];
		VirtualLogList[] items = new VirtualLogList[1];
		FlowPane pane = new FlowPane();
		fx(() -> {
			try {
				controller[0] = new MainController();
				items[0] = new VirtualLogList(model);
				view = new LogListView();
				view.setItems(items[0]);
				Class<?> stateType = Class.forName(MainController.class.getName() + "$TabState");
				var constructor = stateType.getDeclaredConstructors()[0];
				constructor.setAccessible(true);
				Object state = constructor.newInstance(file.toFile(), "Default", model, items[0], view);
				Tab tab = new Tab();
				tab.setUserData(state);
				TabPane tabs = new TabPane(tab);
				setField(controller[0], "tabPane", tabs);
				setField(controller[0], "highlightsPane", pane);
				var rules = MainController.class.getDeclaredField("highlightRules");
				rules.setAccessible(true);
				@SuppressWarnings("unchecked")
				java.util.List<HighlightRule> values = (java.util.List<HighlightRule>) rules.get(controller[0]);
				values.add(new HighlightRule("ERROR", "#ff8080", false));
				count(controller[0]);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		awaitCount(pane, 0);
		Files.writeString(file, "OR\nERROR", StandardOpenOption.APPEND);
		model.updateIndex();
		fx(() -> {
			items[0].publish(model.snapshot());
			count(controller[0]);
		});
		awaitCount(pane, 2);
		Files.writeString(file, "quiet");
		model.updateIndex();
		fx(() -> {
			items[0].publish(model.snapshot());
			count(controller[0]);
		});
		awaitCount(pane, 0);
	}

	@Test
	void errorRowsAreDistinctAndCannotBeCopiedAsText() throws Exception {
		LogLine error = LogLine.readError(2, "Line exceeds read limit");
		assertEquals("first" + System.lineSeparator() + "last", MainController.selectedText(
				java.util.List.of(new LogLine(1, "first"), new LogLine(3, "last"))));
		assertThrows(IllegalStateException.class, () -> MainController.selectedText(
				java.util.List.of(new LogLine(1, "first"), error, new LogLine(3, "last"))));
		fx(() -> {
			try {
				MainController controller = new MainController();
				ListCell<LogLine> cell = new ListCell<>();
				var handle = MainController.class.getDeclaredMethod("handleEmptyItem", ListCell.class,
						LogLine.class, boolean.class);
				handle.setAccessible(true);
				assertEquals(true, handle.invoke(controller, cell, error, false));
				assertTrue(cell.getText().contains("Line 2 unavailable"));
				assertNull(cell.getGraphic());
				assertNotNull(cell.getTooltip());
				assertEquals(false, handle.invoke(controller, cell, new LogLine(3, "real text"), false));
				assertNull(cell.getText());
				assertNull(cell.getTooltip());
				assertEquals(true, handle.invoke(controller, cell, null, true));
				assertEquals("", cell.getStyle());
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	@Test
	void oversizedLineMakesCountsUnavailableAndSearchIncomplete() throws Exception {
		Path file = tempDir.resolve("oversized-scan.log");
		Files.writeString(file, "ERROR");
		LogFileModel model = new LogFileModel(file);
		MainController[] controller = new MainController[1];
		VirtualLogList[] items = new VirtualLogList[1];
		Object[] state = new Object[1];
		FlowPane pane = new FlowPane();
		TabPane occurrences = new TabPane();
		HighlightRule rule = new HighlightRule("ERROR", "#ff8080", false);
		fx(() -> {
			try {
				controller[0] = new MainController();
				items[0] = new VirtualLogList(model);
				view = new LogListView();
				view.setItems(items[0]);
				Class<?> stateType = Class.forName(MainController.class.getName() + "$TabState");
				var constructor = stateType.getDeclaredConstructors()[0];
				constructor.setAccessible(true);
				state[0] = constructor.newInstance(file.toFile(), "Default", model, items[0], view);
				Tab tab = new Tab();
				tab.setUserData(state[0]);
				setField(controller[0], "tabPane", new TabPane(tab));
				setField(controller[0], "occurrencesTabPane", occurrences);
				setField(controller[0], "highlightsPane", pane);
				var rules = MainController.class.getDeclaredField("highlightRules");
				rules.setAccessible(true);
				@SuppressWarnings("unchecked")
				java.util.List<HighlightRule> values = (java.util.List<HighlightRule>) rules.get(controller[0]);
				values.add(rule);
				count(controller[0]);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		awaitCount(pane, 1);
		Files.writeString(file, "ERROR\n");
		try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(file.toFile(), "rw")) {
			output.setLength(2 * 1024 * 1024);
		}
		model.updateIndex();
		Thread[] workers = new Thread[2];
		fx(() -> {
			try {
				items[0].publish(model.snapshot());
				setField(controller[0], "lastUpdateHighlightsBar", 0L);
				count(controller[0]);
				var counter = MainController.class.getDeclaredField("highlightCounterThread");
				counter.setAccessible(true);
				workers[0] = (Thread) counter.get(controller[0]);
				var search = MainController.class.getDeclaredMethod("showOccurrences", HighlightRule.class);
				search.setAccessible(true);
				search.invoke(controller[0], rule);
				workers[1] = (Thread) occurrences.getTabs().getFirst().getProperties().get("searchThread");
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		for (Thread worker : workers) {
			assertNotNull(worker);
			worker.join(10000);
			assertFalse(worker.isAlive());
		}
		fx(() -> {
			assertEquals("ERROR (unavailable)",
					((Label) ((HBox) pane.getChildren().getFirst()).getChildren().getFirst()).getText());
			assertTrue(((Label) pane.getChildren().getLast()).getText().startsWith("Highlight counts unavailable:"));
			try {
				var counts = state[0].getClass().getDeclaredField("highlightCounts");
				counts.setAccessible(true);
				assertNull(counts.get(state[0]));
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
			assertEquals(1, occurrences.getTabs().size());
			Tab tab = occurrences.getTabs().getFirst();
			assertTrue(tab.getText().startsWith("Search failed:"));
			var layout = (javafx.scene.layout.VBox) tab.getContent();
			assertEquals(1, layout.getChildren().size());
			assertTrue(((Label) layout.getChildren().getFirst()).getText().startsWith("Search incomplete;"));
		});
	}

	private void awaitCount(FlowPane pane, int expected) throws Exception {
		for (int attempt = 0; attempt < 30; attempt++) {
			boolean[] found = new boolean[1];
			fx(() -> found[0] = !pane.getChildren().isEmpty()
					&& pane.getChildren().getFirst() instanceof HBox tag
					&& ((Label) tag.getChildren().getFirst()).getText()
					.equals("ERROR (" + expected + ")"));
			if (found[0])
				return;
			pulses();
		}
		fail("Highlight count did not become " + expected);
	}

	private static void count(MainController controller) {
		try {
			var method = MainController.class.getDeclaredMethod("updateHighlightsBar");
			method.setAccessible(true);
			method.invoke(controller);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
		var field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private ListCell<?> cell(int index) {
		return view.lookupAll(".list-cell").stream()
				.filter(node -> node instanceof ListCell<?> cell && cell.getIndex() == index)
				.map(node -> (ListCell<?>) node).findFirst().orElseThrow();
	}

	private void mouse(ListCell<?> cell, javafx.event.EventType<MouseEvent> type) {
		var point = cell.localToScene(20, 12);
		cell.fireEvent(new MouseEvent(type, point.getX(), point.getY(), point.getX(), point.getY(),
				MouseButton.PRIMARY, 1, false, false, false, false,
				type != MouseEvent.MOUSE_RELEASED, false, false, false, false, false,
				new PickResult(cell, 20, 12)));
	}
}