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
import ch.eitchnet.luminelog.model.OccurrenceLogList;
import ch.eitchnet.luminelog.model.VirtualLogList;
import ch.eitchnet.luminelog.service.FileOpenService;
import ch.eitchnet.luminelog.service.ScanService;
import ch.eitchnet.luminelog.service.LineLoadingService;
import ch.eitchnet.luminelog.service.TailScheduler;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class LogListViewTest {

	private Stage stage;
	private LogListView view;
	private final java.util.List<MainController> controllers = new java.util.ArrayList<>();
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

	@Test
	void selectingLargeRangesDoesNotScheduleOffscreenReads() throws Exception {
		Path path = tempDir.resolve("large-selection.log");
		Files.writeString(path, "line\n".repeat(20000));
		var model = new LogFileModel(path);
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		try (var loader = new LineLoadingService(callbacks::add)) {
			VirtualLogList[] items = new VirtualLogList[1];
			fx(() -> {
				view = new LogListView();
				items[0] = new VirtualLogList(model, loader);
				view.setItems(items[0]);
				assertTrue(items[0].get(0).isLoading());
			});
			Runnable completion = callbacks.poll(5, TimeUnit.SECONDS);
			assertNotNull(completion);
			fx(() -> {
				completion.run();
				assertTrue(items[0].get(0).isAvailable());
				// Finish the single adjacent prefetch before measuring selection work.
			});
			Runnable prefetch = callbacks.poll(5, TimeUnit.SECONDS);
			assertNotNull(prefetch);
			fx(prefetch);
			fx(() -> {
				assertTrue(items[0].get(0).isAvailable(), "Prefetch must preserve visible text");
				view.getSelectionModel().selectRange(0, 20000);
				assertEquals(0, loader.activeJobs() + loader.queuedJobs(),
						"Selection must not request uncached offscreen text");
				assertTrue(callbacks.isEmpty(), "Selection must not enqueue read completions");
				view.getSelectionModel().clearSelection();
				view.getSelectionModel().selectRange(0, 20);
				view.followingProperty().set(false);
				view.setFixedCellSize(24);
				stage = new Stage();
				stage.setScene(new Scene(new StackPane(view), 500, 300));
				stage.show();
			});
			pulses();
			fx(() -> {
				assertEquals(20, view.getSelectionModel().getSelectedIndices().size());
				for (var node : view.lookupAll(".list-cell")) {
					if (node instanceof ListCell<?> cell && !cell.isEmpty())
						assertTrue(((LogLine) cell.getItem()).isAvailable(), "Selected visible rows must stay loaded");
				}
				assertEquals(0, loader.activeJobs() + loader.queuedJobs());
				assertTrue(callbacks.isEmpty());
				items[0].close();
			});
		}
	}

	@Test
	void selectingOccurrenceRangesDoesNotScheduleOffscreenReads() throws Exception {
		Path path = tempDir.resolve("occurrence-selection.log");
		Files.writeString(path, "match\n".repeat(5000));
		var model = new LogFileModel(path);
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		try (var loader = new LineLoadingService(callbacks::add)) {
			VirtualLogList[] live = new VirtualLogList[1];
			OccurrenceLogList[] results = new OccurrenceLogList[1];
			fx(() -> {
				view = new LogListView();
				live[0] = new VirtualLogList(model, loader);
				var identifiers = new ch.eitchnet.luminelog.model.ChunkedOccurrenceIndex.Builder();
				for (int row = 0; row < 5000; row++)
					identifiers.add(row);
				results[0] = new OccurrenceLogList(model, live[0], live[0].snapshot(), identifiers.build(), loader);
				view.setItems(results[0]);
				assertTrue(results[0].get(0).isLoading());
			});
			Runnable completion = callbacks.poll(5, TimeUnit.SECONDS);
			assertNotNull(completion);
			fx(() -> {
				completion.run();
				view.getSelectionModel().selectAll();
				assertEquals(java.util.List.of(0), view.getSelectionModel().getSelectedIndices());
				assertEquals(0, loader.activeJobs() + loader.queuedJobs());
				assertTrue(callbacks.isEmpty());
				assertTrue(results[0].get(0).isAvailable());
				results[0].close();
				live[0].close();
			});
		}
	}

	@AfterEach
	void close() throws Exception {
		fx(() -> {
			controllers.forEach(MainController::dispose);
			if (view != null)
				view.dispose();
			if (stage != null)
				stage.close();
		});
	}

	@Test
	void hiddenTailAnimationHasNoCallbacksAndResumesOnVisibility() throws Exception {
		show();
		fx(() -> {
			view.setVisible(false);
			view.getItems().add(new LogLine(101, "hidden append"));
			view.requestTail();
		});
		assertAnimationSuspended();
		fx(() -> view.setVisible(true));
		pulses();
		fx(() -> {
			assertEquals(0, field(view, "tailPulses"));
			ScrollBar bar = (ScrollBar) view.lookup(".scroll-bar:vertical");
			assertEquals(bar.getMax(), bar.getValue(), 0.0001);
			assertTrue(view.followingProperty().get());
		});
		assertAnimationSuspended();
	}

	@Test
	void ancestorHiddenTailAnimationHasNoCallbacks() throws Exception {
		show();
		fx(() -> {
			view.getParent().setVisible(false);
			view.requestTail();
		});
		assertAnimationSuspended();
		fx(() -> view.getParent().setVisible(true));
		pulses();
		fx(() -> assertEquals(0, field(view, "tailPulses")));
	}

	@Test
	void pressedTailAnimationWaitsForRelease() throws Exception {
		show();
		fx(() -> {
			mouse(cell(95), MouseEvent.MOUSE_PRESSED);
			view.requestTail();
		});
		assertAnimationSuspended();
		fx(() -> mouse(cell(95), MouseEvent.MOUSE_RELEASED));
		pulses();
		fx(() -> {
			assertEquals(0, field(view, "tailPulses"));
			assertTrue(view.followingProperty().get());
		});
	}

	private void assertAnimationSuspended() throws Exception {
		long[] before = new long[1];
		fx(() -> before[0] = (long) field(view, "animationCallbacks"));
		pulses();
		fx(() -> assertEquals(before[0], field(view, "animationCallbacks"),
				"Ineligible views must receive no animation callbacks"));
	}

	@Test
	void emptyViewWaitsForItemsWithoutAnimationCallbacks() throws Exception {
		show();
		fx(() -> {
			view.getItems().clear();
			view.requestTail();
		});
		assertAnimationSuspended();
		fx(() -> {
			view.getItems().add(new LogLine(1, "first line"));
			assertTrue((boolean) field(view, "animationRunning"));
		});
		pulses();
		fx(() -> assertEquals(0, field(view, "tailPulses")));
		assertAnimationSuspended();
	}

	@Test
	void zeroHeightTailAnimationResumesAfterLayout() throws Exception {
		show();
		fx(() -> {
			view.setManaged(false);
			view.resize(500, 0);
			view.requestTail();
		});
		assertAnimationSuspended();
		fx(() -> {
			assertEquals(2, field(view, "tailPulses"));
			view.resize(500, 300);
		});
		pulses();
		fx(() -> assertEquals(0, field(view, "tailPulses")));
	}

	@Test
	void detachedTailAnimationResumesUnderNewAncestors() throws Exception {
		show();
		fx(() -> {
			((StackPane) stage.getScene().getRoot()).getChildren().clear();
			view.requestTail();
		});
		assertAnimationSuspended();
		fx(() -> {
			StackPane wrapper = new StackPane(view);
			wrapper.setVisible(false);
			((StackPane) stage.getScene().getRoot()).getChildren().add(wrapper);
		});
		assertAnimationSuspended();
		fx(() -> view.getParent().setVisible(true));
		pulses();
		fx(() -> assertEquals(0, field(view, "tailPulses")));
	}

	@Test
	void hiddenWindowTailAnimationResumesOnShow() throws Exception {
		show();
		fx(() -> {
			stage.hide();
			view.requestTail();
		});
		assertAnimationSuspended();
		fx(() -> stage.show());
		pulses();
		fx(() -> assertEquals(0, field(view, "tailPulses")));
	}

	@Test
	void inactiveTabTailAnimationWaitsForActivation() throws Exception {
		show();
		Tab[] logTab = new Tab[1];
		fx(() -> {
			((StackPane) stage.getScene().getRoot()).getChildren().clear();
			logTab[0] = new Tab("Log", new StackPane(view));
			TabPane tabs = new TabPane(logTab[0], new Tab("Other", new Label("Other")));
			((StackPane) stage.getScene().getRoot()).getChildren().add(tabs);
			tabs.getSelectionModel().select(1);
			view.getItems().add(new LogLine(101, "inactive append"));
			view.requestTail();
		});
		assertAnimationSuspended();
		fx(() -> logTab[0].getTabPane().getSelectionModel().select(logTab[0]));
		pulses();
		fx(() -> {
			assertEquals(0, field(view, "tailPulses"));
			ScrollBar bar = (ScrollBar) view.lookup(".scroll-bar:vertical");
			assertEquals(bar.getMax(), bar.getValue(), 0.0001);
		});
	}

	@Test
	void dragAnimationRunsOnlyAtRunnableEdgesAndCancelsImmediately() throws Exception {
		show();
		fx(() -> {
			view.followingProperty().set(false);
			view.scrollTo(0);
		});
		pulses();
		fx(() -> {
			mouse(cell(1), MouseEvent.MOUSE_PRESSED);
			mouse(cell(4), MouseEvent.MOUSE_DRAGGED);
			assertFalse((boolean) field(view, "animationRunning"), "Interior dragging has no periodic work");
		});
		assertAnimationSuspended();
		fx(() -> {
			dragAt(view.getHeight() + 20);
			assertTrue((boolean) field(view, "animationRunning"));
			view.setVisible(false);
		});
		var selection = new java.util.ArrayList<Integer>();
		fx(() -> selection.addAll(view.getSelectionModel().getSelectedIndices()));
		assertAnimationSuspended();
		fx(() -> {
			assertEquals(selection, view.getSelectionModel().getSelectedIndices());
			view.setVisible(true);
			assertTrue((boolean) field(view, "animationRunning"));
			view.cancelSelectionGesture();
			assertFalse((boolean) field(view, "animationRunning"));
			assertEquals(selection, view.getSelectionModel().getSelectedIndices());
		});
		assertAnimationSuspended();
		fx(() -> {
			view.scrollTo(0);
			view.layout();
			mouse(cell(1), MouseEvent.MOUSE_PRESSED);
			dragAt(-20);
			assertEquals(0, view.getSelectionModel().getSelectedIndices().getFirst());
			assertFalse((boolean) field(view, "animationRunning"), "Cannot scroll beyond the first row");
		});
		assertAnimationSuspended();
	}

	private void dragAt(double y) {
		var point = view.localToScene(20, y);
		view.fireEvent(new MouseEvent(MouseEvent.MOUSE_DRAGGED, point.getX(), point.getY(),
				point.getX(), point.getY(), MouseButton.PRIMARY, 1, false, false, false, false,
				true, false, false, false, false, false, new PickResult(view, 20, y)));
	}

	@Test
	void followDisabledAndDisposedViewsCannotRestartAnimation() throws Exception {
		show();
		fx(() -> {
			view.requestTail();
			view.followingProperty().set(false);
			assertFalse((boolean) field(view, "animationRunning"));
		});
		assertAnimationSuspended();
		fx(() -> {
			view.followingProperty().set(true);
			assertTrue((boolean) field(view, "animationRunning"));
			view.dispose();
			view.requestTail();
			view.setVisible(false);
			view.setVisible(true);
			view.followingProperty().set(false);
			view.followingProperty().set(true);
			view.resize(400, 200);
			assertFalse((boolean) field(view, "animationRunning"));
			assertTrue(((java.util.List<?>) field(view, "eligibilityObservables")).isEmpty());
		});
		assertAnimationSuspended();
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
				controllers.add(controller[0]);
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
		assertThrows(IllegalStateException.class,
				() -> MainController.selectedText(java.util.List.of(LogLine.loading(1))));
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
				assertFalse(cell.isDisable());
				assertNull(cell.getText());
				assertNull(cell.getTooltip());
				assertEquals(true, handle.invoke(controller, cell, LogLine.loading(4), false));
				assertTrue(cell.isDisable());
				assertTrue(cell.getText().contains("Loading line 4"));
				assertEquals(true, handle.invoke(controller, cell, null, true));
				assertEquals("", cell.getStyle());
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	@Test
	void presentationRowsCannotBeSelectedByKeyboardRangeOrSelectAll() throws Exception {
		fx(() -> {
			view = new LogListView();
			view.setItems(FXCollections.observableArrayList(new LogLine(1, "one"), LogLine.loading(2),
					LogLine.readError(3, "unavailable"), new LogLine(4, "four")));
			var selection = view.getSelectionModel();
			selection.select(1);
			assertTrue(selection.isEmpty());
			selection.clearAndSelect(2);
			assertTrue(selection.isEmpty());
			selection.selectRange(0, 4);
			assertEquals(java.util.List.of(0, 3), java.util.List.copyOf(selection.getSelectedIndices()));
			selection.clearSelection();
			selection.selectAll();
			assertEquals(java.util.List.of(0, 3), java.util.List.copyOf(selection.getSelectedIndices()));
			assertEquals("one" + System.lineSeparator() + "four", MainController.selectedText(selection.getSelectedItems()));
			selection.clearAndSelect(0);
			selection.selectNext();
			assertFalse(selection.isSelected(1));
			view.getItems().set(0, LogLine.loading(1));
			assertTrue(selection.isEmpty());
		});
	}

	@Test
	void navigationAndPulsesContinueWhileLineDeliveryIsHeld() throws Exception {
		Path file = tempDir.resolve("delayed-lines.log");
		Files.writeString(file, "line\n".repeat(50));
		LogFileModel model = new LogFileModel(file);
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		VirtualLogList[] items = new VirtualLogList[1];
		try (var loader = new LineLoadingService(callbacks::add)) {
			fx(() -> {
				items[0] = new VirtualLogList(model, loader);
				assertTrue(items[0].get(0).isLoading());
				view = new LogListView();
				view.setFixedCellSize(24);
				view.setItems(items[0]);
				view.followingProperty().set(false);
				stage = new Stage();
				stage.setScene(new Scene(new StackPane(view), 500, 300));
				stage.show();
				view.navigateTo(20);
				view.getSelectionModel().select(20);
				assertTrue(view.getSelectionModel().isEmpty());
			});
			Runnable delivery = callbacks.poll(5, TimeUnit.SECONDS);
			assertNotNull(delivery);
			pulses();
			fx(() -> {
				assertTrue(items[0].get(20).isLoading());
				delivery.run();
				assertEquals("line", items[0].get(20).content());
				assertEquals(20, view.getSelectionModel().getSelectedIndex());
				items[0].close();
			});
		} finally {
			if (items[0] != null)
				fx(items[0]::close);
		}
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
				controllers.add(controller[0]);
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
		fx(() -> {
			try {
				items[0].publish(model.snapshot());
				setField(controller[0], "lastUpdateHighlightsBar", 0L);
				count(controller[0]);
				var search = MainController.class.getDeclaredMethod("showOccurrences", HighlightRule.class);
				search.setAccessible(true);
				search.invoke(controller[0], rule);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		awaitUi(() -> !pane.getChildren().isEmpty() && pane.getChildren().getLast() instanceof Label label
				&& label.getText().startsWith("Highlight counts unavailable:")
				&& occurrences.getTabs().getFirst().getText().startsWith("Search failed:"));
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

	@Test
	void refreshingSearchSuppressesAlreadyCompletedOldResult() throws Exception {
		Path file = tempDir.resolve("refresh-scan.log");
		Files.writeString(file, "ERROR old");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		try (var service = new ScanService(1, 8, callbacks::add)) {
			ScanUi ui = scanUi(file, service);
			fx(() -> search(ui));
			workerFence(service);
			Files.writeString(file, "ERROR replacement\nERROR second");
			ui.model().updateIndex();
			fx(() -> {
				ui.items().publish(ui.model().snapshot());
				search(ui);
			});
			workerFence(service);
			fx(() -> {
				drain(callbacks);
				assertEquals(1, ui.occurrences().getTabs().size());
				var layout = (javafx.scene.layout.VBox) ui.occurrences().getTabs().getFirst().getContent();
				assertTrue(((Label) layout.getChildren().getFirst()).getText().startsWith("Found 2 occurrences."));
				var list = (LogListView) layout.getChildren().get(1);
				assertInstanceOf(OccurrenceLogList.class, list.getItems());
				assertTrue(list.getItems().getFirst().isLoading());
			});
			awaitUi(() -> occurrenceView(ui).getItems().stream().allMatch(LogLine::isAvailable));
			fx(() -> {
				assertEquals(java.util.List.of(new LogLine(1, "ERROR replacement"), new LogLine(2, "ERROR second")),
						occurrenceView(ui).getItems());
			});
		}
	}

	private static LogListView occurrenceView(ScanUi ui) {
		var layout = (javafx.scene.layout.VBox) ui.occurrences().getTabs().getFirst().getContent();
		return (LogListView) layout.getChildren().getLast();
	}

	@Test
	void identifierResultsPreserveCopyNavigationAndReleaseOnRefreshAndClose() throws Exception {
		Path file = tempDir.resolve("identifier-results.log");
		Files.writeString(file, "skip\nERROR one\nskip\nERROR two");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		try (var service = new ScanService(1, 8, callbacks::add)) {
			ScanUi ui = scanUi(file, service);
			fx(() -> search(ui));
			workerFence(service);
			fx(() -> {
				drain(callbacks);
				LogLine loading = occurrenceView(ui).getItems().getFirst();
				assertTrue(loading.isLoading());
				assertThrows(IllegalStateException.class, () -> MainController.selectedText(java.util.List.of(loading)));
			});
			awaitUi(() -> occurrenceView(ui).getItems().stream().allMatch(LogLine::isAvailable));
			OccurrenceLogList[] previous = new OccurrenceLogList[1];
			fx(() -> {
				LogListView result = occurrenceView(ui);
				previous[0] = (OccurrenceLogList) result.getItems();
				assertEquals(java.util.List.of(new LogLine(2, "ERROR one"), new LogLine(4, "ERROR two")), result.getItems());
				result.getSelectionModel().selectAll();
				assertEquals("ERROR one" + System.lineSeparator() + "ERROR two",
						MainController.selectedText(result.getSelectionModel().getSelectedItems()));
				result.getSelectionModel().clearAndSelect(1);
				result.getOnMouseClicked().handle(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
						MouseButton.PRIMARY, 2, false, false, false, false, false, false, false, false, false, false, null));
			});
			awaitUi(() -> view.getSelectionModel().getSelectedIndex() == 3);
			fx(() -> {
				search(ui);
				assertEquals(0, previous[0].size(), "Refresh releases the old identifier store");
			});
			workerFence(service);
			fx(() -> drain(callbacks));
			awaitUi(() -> occurrenceView(ui).getItems().stream().allMatch(LogLine::isAvailable));
			fx(() -> {
				previous[0] = (OccurrenceLogList) occurrenceView(ui).getItems();
				ui.occurrences().getTabs().clear();
				assertEquals(0, previous[0].size(), "Closing results releases the identifier store");
			});
		}
	}

	@Test
	void spilledResultsPreserveCopyNavigationAndDeleteFilesOnRefreshCloseAndShutdown() throws Exception {
		Path file = tempDir.resolve("spilled-results.log");
		Files.writeString(file, "skip\nERROR match\n".repeat(70000));
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		try (var service = new ScanService(1, 8, callbacks::add)) {
			ScanUi ui = scanUi(file, service);
			Path[] storage = new Path[1];
			for (int lifecycle = 0; lifecycle < 3; lifecycle++) {
				fx(() -> search(ui));
				workerFence(service);
				fx(() -> drain(callbacks));
				awaitUi(() -> occurrenceView(ui).getItems().get(69999).isAvailable());
				fx(() -> {
					var result = occurrenceView(ui);
					try {
						var field = OccurrenceLogList.class.getDeclaredField("index");
						field.setAccessible(true);
						var index = (ch.eitchnet.luminelog.model.SpillableOccurrenceIndex) field.get(result.getItems());
						assertTrue(index.isSpilled());
						storage[0] = index.storagePath();
					} catch (ReflectiveOperationException e) {
						throw new AssertionError(e);
					}
					assertTrue(Files.exists(storage[0]));
					assertEquals(70000, result.getItems().size());
					assertEquals(new LogLine(140000, "ERROR match"), result.getItems().get(69999));
					result.getSelectionModel().clearAndSelect(69999);
					assertEquals("ERROR match", MainController.selectedText(result.getSelectionModel().getSelectedItems()));
					result.getOnMouseClicked().handle(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
							MouseButton.PRIMARY, 2, false, false, false, false, false, false, false, false, false, false, null));
				});
				awaitUi(() -> view.getSelectionModel().getSelectedIndex() == 139999);
				int action = lifecycle;
				fx(() -> {
					if (action == 0)
						search(ui);
					else if (action == 1)
						ui.occurrences().getTabs().clear();
					else
						ui.controller().dispose();
					assertFalse(Files.exists(storage[0]), "Owned spill must be deleted at its lifecycle boundary");
				});
			}
		}
	}

	@Test
	void rewrittenOccurrenceRowsCannotBeCopiedOrNavigated() throws Exception {
		Path file = tempDir.resolve("invalid-result.log");
		Files.writeString(file, "skip\nERROR old");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		try (var service = new ScanService(1, 8, callbacks::add)) {
			ScanUi ui = scanUi(file, service);
			fx(() -> search(ui));
			workerFence(service);
			fx(() -> drain(callbacks));
			awaitUi(() -> occurrenceView(ui).getItems().getFirst().isAvailable());
			fx(() -> occurrenceView(ui).getSelectionModel().select(0));
			Files.writeString(file, "replacement");
			ui.model().updateIndex();
			fx(() -> {
				ui.items().publish(ui.model().snapshot());
				LogListView result = occurrenceView(ui);
				assertTrue(result.getSelectionModel().getSelectedItems().stream().noneMatch(LogLine::isAvailable));
				LogLine row = result.getItems().getFirst();
				assertTrue(row.isReadError());
				assertThrows(IllegalStateException.class, () -> MainController.selectedText(java.util.List.of(row)));
				result.getSelectionModel().select(0);
				result.getOnMouseClicked().handle(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
						MouseButton.PRIMARY, 2, false, false, false, false, false, false, false, false, false, false, null));
				assertEquals(-1, view.getSelectionModel().getSelectedIndex());
			});
		}
	}

	@Test
	void closingSourceSuppressesPendingCountAndSearchPublication() throws Exception {
		Path file = tempDir.resolve("closed-scans.log");
		Files.writeString(file, "ERROR");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		try (var service = new ScanService(1, 8, callbacks::add)) {
			ScanUi ui = scanUi(file, service);
			fx(() -> {
				count(ui.controller());
				search(ui);
			});
			workerFence(service);
			fx(() -> {
				ui.tabs().getTabs().clear();
				drain(callbacks);
				assertTrue(ui.occurrences().getTabs().isEmpty());
				assertTrue(ui.highlights().getChildren().isEmpty());
			});
		}
	}

	@Test
	void closingResultTabAndDisposingControllerSuppressPendingResults() throws Exception {
		Path file = tempDir.resolve("dispose-scans.log");
		Files.writeString(file, "ERROR");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		try (var service = new ScanService(1, 8, callbacks::add)) {
			ScanUi ui = scanUi(file, service);
			fx(() -> search(ui));
			workerFence(service);
			fx(() -> {
				Tab removed = ui.occurrences().getTabs().getFirst();
				ui.occurrences().getTabs().clear();
				drain(callbacks);
				assertFalse(removed.getProperties().containsKey("searchJob"));
				assertEquals(3, ((javafx.scene.layout.VBox) removed.getContent()).getChildren().size());
				count(ui.controller());
				search(ui);
			});
			workerFence(service);
			fx(() -> {
				var layout = (javafx.scene.layout.VBox) ui.occurrences().getTabs().getFirst().getContent();
				ui.controller().dispose();
				ui.controller().dispose();
				drain(callbacks);
				assertTrue(ui.highlights().getChildren().isEmpty());
				assertEquals(3, layout.getChildren().size());
				assertTrue(layout.getChildren().stream().noneMatch(LogListView.class::isInstance));
			});
		}
	}

	@Test
	void queuedSearchCanBeCancelledAndOverloadIsVisible() throws Exception {
		Path file = tempDir.resolve("queued-scans.log");
		Files.writeString(file, "ERROR");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (var service = new ScanService(1, 2, callbacks::add)) {
			service.submit(new Object(), job -> {
				started.countDown();
				release.await();
				return null;
			}, (_, _) -> { });
			assertTrue(started.await(10, TimeUnit.SECONDS));
			ScanUi ui = scanUi(file, service);
			fx(() -> {
				count(ui.controller());
				search(ui);
				invoke(ui.controller(), "showOccurrences", new Class<?>[] { HighlightRule.class },
						new HighlightRule("different", "#ff8080", false));
				drain(callbacks);
				var first = (javafx.scene.layout.VBox) ui.occurrences().getTabs().getFirst().getContent();
				assertEquals("Search queued...", ((Label) first.getChildren().getFirst()).getText());
				var rejected = (javafx.scene.layout.VBox) ui.occurrences().getTabs().getLast().getContent();
				assertTrue(((Label) rejected.getChildren().getFirst()).getText().startsWith("Scan capacity reached;"));
				((javafx.scene.control.Button) first.getChildren().get(2)).fire();
				drain(callbacks);
				assertEquals("Search cancelled; no results published.",
						((Label) first.getChildren().getFirst()).getText());
				assertEquals(1, first.getChildren().size());
				ui.controller().dispose();
				drain(callbacks);
			});
		} finally {
			release.countDown();
		}
	}

	@Test
	void equivalentRulesAndColorChangesReuseCompletedCountsWithoutScanning() throws Exception {
		Path file = tempDir.resolve("equivalent-counts.log");
		Files.writeString(file, "ERROR\nERROR");
		try (var service = new ScanService(Platform::runLater)) {
			ScanUi ui = scanUi(file, service);
			fx(() -> count(ui.controller()));
			awaitCount(ui.highlights(), 2);
			fx(() -> {
				Object counts = field(ui.tabs().getTabs().getFirst().getUserData(), "highlightCounts");
				for (int i = 0; i < 100; i++) {
					replaceCountRules(ui, new HighlightRule("ERROR", i % 2 == 0 ? "blue" : "red", false));
					resetCountDelay(ui);
					count(ui.controller());
					assertEquals(false, field(ui.controller(), "countPending"), "Equivalent matching inputs must not scan");
					assertSame(counts, field(ui.tabs().getTabs().getFirst().getUserData(), "highlightCounts"));
					assertTrue(ui.highlights().getChildren().getFirst().getStyle().contains(i % 2 == 0 ? "blue" : "red"));
				}
			});
		}
	}

	@Test
	void inPlacePatternAndModeEditsInvalidateCounts() throws Exception {
		Path file = tempDir.resolve("edited-counts.log");
		Files.writeString(file, "ERROR\nERR.R");
		try (var service = new ScanService(Platform::runLater)) {
			ScanUi ui = scanUi(file, service);
			fx(() -> count(ui.controller()));
			awaitCount(ui.highlights(), 1);
			fx(() -> {
				ui.rule().setPattern("ERR.R");
				resetCountDelay(ui);
				count(ui.controller());
			});
			awaitUi(() -> countLabel(ui).equals("ERR.R (1)"));
			fx(() -> {
				ui.rule().setIsRegex(true);
				resetCountDelay(ui);
				count(ui.controller());
			});
			awaitUi(() -> countLabel(ui).equals("ERR.R (2)"));
		}
	}

	@Test
	void pendingCountUsesCapturedMatchingInputsAndLatestEquivalentStyle() throws Exception {
		Path file = tempDir.resolve("pending-counts.log");
		Files.writeString(file, "ERROR\nERROR");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		try (var service = new ScanService(1, 8, callbacks::add)) {
			ScanUi ui = scanUi(file, service);
			fx(() -> count(ui.controller()));
			workerFence(service);
			fx(() -> {
				replaceCountRules(ui, new HighlightRule("ERROR", "blue", false));
				count(ui.controller());
				drain(callbacks);
				assertEquals("ERROR (2)", countLabel(ui));
				assertTrue(ui.highlights().getChildren().getFirst().getStyle().contains("blue"));
			});
		}
	}

	@Test
	void queuedCountCapturesRulesBeforeWorkerStarts() throws Exception {
		Path file = tempDir.resolve("queued-count-inputs.log");
		Files.writeString(file, "ERROR\nERROR");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (var service = new ScanService(1, 8, callbacks::add)) {
			service.submit(new Object(), _ -> {
				started.countDown();
				release.await();
				return null;
			}, (_, _) -> { });
			assertTrue(started.await(10, TimeUnit.SECONDS));
			ScanUi ui = scanUi(file, service);
			fx(() -> {
				count(ui.controller());
				ui.rule().setPattern("missing");
			});
			release.countDown();
			workerFence(service);
			fx(() -> {
				// Restoring the captured inputs makes the old result applicable again.
				ui.rule().setPattern("ERROR");
				drain(callbacks);
				assertEquals("ERROR (2)", countLabel(ui));
			});
		} finally {
			release.countDown();
		}
	}

	@Test
	void staleCountCannotPublishAfterInPlaceEditBeforeDelivery() throws Exception {
		Path file = tempDir.resolve("stale-counts.log");
		Files.writeString(file, "ERROR\nERROR");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		try (var service = new ScanService(1, 8, callbacks::add)) {
			ScanUi ui = scanUi(file, service);
			fx(() -> count(ui.controller()));
			workerFence(service);
			fx(() -> {
				ui.rule().setPattern("missing");
				drain(callbacks);
				assertFalse(countLabel(ui).equals("missing (2)"), "Old worker counts must not be relabelled with edited inputs");
				resetCountDelay(ui);
				count(ui.controller());
			});
			workerFence(service);
			fx(() -> {
				drain(callbacks);
				assertEquals("missing (0)", countLabel(ui));
			});
		}
	}

	private static String countLabel(ScanUi ui) {
		if (ui.highlights().getChildren().isEmpty() || !(ui.highlights().getChildren().getFirst() instanceof HBox tag))
			return "";
		return ((Label) tag.getChildren().getFirst()).getText();
	}

	@SuppressWarnings("unchecked")
	private static void replaceCountRules(ScanUi ui, HighlightRule rule) {
		var rules = (java.util.List<HighlightRule>) field(ui.controller(), "highlightRules");
		rules.clear();
		rules.add(rule);
	}

	private static void resetCountDelay(ScanUi ui) {
		try {
			setField(ui.controller(), "lastUpdateHighlightsBar", 0L);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private record ScanUi(MainController controller, LogFileModel model, VirtualLogList items, TabPane tabs,
			TabPane occurrences, FlowPane highlights, HighlightRule rule) {
	}

	private ScanUi scanUi(Path file, ScanService service) throws Exception {
		LogFileModel model = new LogFileModel(file);
		ScanUi[] result = new ScanUi[1];
		fx(() -> {
			try {
				MainController controller = new MainController(service);
				controllers.add(controller);
				VirtualLogList items = new VirtualLogList(model);
				view = new LogListView();
				view.setItems(items);
				var type = Class.forName(MainController.class.getName() + "$TabState");
				var constructor = type.getDeclaredConstructors()[0];
				constructor.setAccessible(true);
				Tab tab = new Tab();
				tab.setUserData(constructor.newInstance(file.toFile(), "Default", model, items, view));
				TabPane tabs = new TabPane(tab);
				TabPane occurrences = new TabPane();
				FlowPane highlights = new FlowPane();
				setField(controller, "tabPane", tabs);
				setField(controller, "occurrencesTabPane", occurrences);
				setField(controller, "highlightsPane", highlights);
				setField(controller, "mainSplitPane", new javafx.scene.control.SplitPane(tabs, occurrences));
				setField(controller, "fontSizeSpinner", new javafx.scene.control.Spinner<Integer>(8, 32, 12));
				var rules = MainController.class.getDeclaredField("highlightRules");
				rules.setAccessible(true);
				@SuppressWarnings("unchecked")
				var values = (java.util.List<HighlightRule>) rules.get(controller);
				HighlightRule rule = new HighlightRule("ERROR", "#ff8080", false);
				values.add(rule);
				tabs.getTabs().addListener((ListChangeListener<Tab>) change -> invoke(controller, "handleRemovedTabs",
						new Class<?>[] { ListChangeListener.Change.class }, change));
				occurrences.getTabs().addListener((ListChangeListener<Tab>) change -> invoke(controller,
						"handleOcurrencesTabChanged", new Class<?>[] { ListChangeListener.Change.class }, change));
				result[0] = new ScanUi(controller, model, items, tabs, occurrences, highlights, rule);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		return result[0];
	}

	private static void search(ScanUi ui) {
		invoke(ui.controller(), "showOccurrences", new Class<?>[] { HighlightRule.class }, ui.rule());
	}

	private static void invoke(Object target, String name, Class<?>[] types, Object... arguments) {
		try {
			var method = target.getClass().getDeclaredMethod(name, types);
			method.setAccessible(true);
			method.invoke(target, arguments);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static void workerFence(ScanService service) throws Exception {
		CountDownLatch reached = new CountDownLatch(1);
		service.submit(new Object(), _ -> {
			reached.countDown();
			return null;
		}, (_, _) -> { });
		assertTrue(reached.await(10, TimeUnit.SECONDS));
	}

	private static void drain(java.util.Queue<Runnable> callbacks) {
		Runnable callback;
		while ((callback = callbacks.poll()) != null)
			callback.run();
	}

	private static void awaitUi(java.util.function.BooleanSupplier condition) throws Exception {
		for (int attempt = 0; attempt < 30; attempt++) {
			boolean[] found = new boolean[1];
			fx(() -> found[0] = condition.getAsBoolean());
			if (found[0])
				return;
			pulses();
		}
		fail("UI state did not arrive");
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

	@Test
	void fileOpenPublishesLoadingTabBeforeAttachingModel() throws Exception {
		Path file = tempDir.resolve("opening.log");
		Files.writeString(file, "one\ntwo");
		fx(() -> {
			MainController controller = openingController();
			invoke(controller, "openFile", new Class<?>[] { java.io.File.class, String.class }, file.toFile(), "Default");
			TabPane tabs = (TabPane) field(controller, "tabPane");
			assertEquals(1, tabs.getTabs().size());
			Tab tab = tabs.getTabs().getFirst();
			assertInstanceOf(javafx.scene.layout.VBox.class, tab.getContent(), "Opening must first publish a loading panel");
			assertNull(field(tab.getUserData(), "logModel"));
		});
	}

	private MainController openingController() {
		return openingController(new FileOpenService(Platform::runLater));
	}

	private MainController openingController(FileOpenService opener) {
		MainController controller = new MainController(new ScanService(Platform::runLater), opener);
		controllers.add(controller);
		try {
			setField(field(controller, "configService"), "configPath", tempDir.resolve("config.json"));
			setField(controller, "tabPane", new TabPane());
			setField(controller, "occurrencesTabPane", new TabPane());
			setField(controller, "highlightsPane", new FlowPane());
			setField(controller, "fontSizeSpinner", new javafx.scene.control.Spinner<Integer>(8, 32, 12));
			setField(controller, "historyMenu", new javafx.scene.control.Menu());
			setField(controller, "statusLabel", new Label());
			setField(controller, "tailCheckBox", new javafx.scene.control.CheckBox());
			var groups = new javafx.scene.control.ComboBox<String>();
			groups.getItems().add("Default");
			setField(controller, "highlightGroupComboBox", groups);
			setField(controller, "currentGroup", "Default");
			TabPane tabs = (TabPane) field(controller, "tabPane");
			tabs.getTabs().addListener((ListChangeListener<Tab>) change -> invoke(controller, "handleRemovedTabs",
					new Class<?>[] { ListChangeListener.Change.class }, change));
			tabs.getSelectionModel().selectedItemProperty().addListener((_, oldTab, newTab) -> invoke(controller,
					"handleActiveTabChanged", new Class<?>[] { Tab.class, Tab.class }, oldTab, newTab));
			return controller;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void inactiveTailingStillIndexesWithFollowPausedAndReleasesRegistrationOnClose() throws Exception {
		Path path = tempDir.resolve("scheduled-tail.log");
		Files.writeString(path, "old");
		LogFileModel model = new LogFileModel(path);
		MainController[] controller = new MainController[1];
		Tab[] tab = new Tab[1];
		fx(() -> {
			controller[0] = openingController();
			tab[0] = readyTailTab(controller[0], model, path);
			((LogListView) tab[0].getContent()).followingProperty().set(false);
			Object state = tab[0].getUserData();
			invoke(controller[0], "startTailing", new Class<?>[] { state.getClass() }, state);
			Tab other = readyTailTab(controller[0], model, path);
			((TabPane) field(controller[0], "tabPane")).getSelectionModel().select(other);
			assertEquals(1, ((TailScheduler) field(controller[0], "tailScheduler")).registrationCount());
		});
		Files.writeString(path, "\nnew", StandardOpenOption.APPEND);
		awaitUi(() -> ((java.util.concurrent.atomic.AtomicReference<?>)
				field(tab[0].getUserData(), "pendingUpdate")).get() != null);
		fx(() -> {
			Object state = tab[0].getUserData();
			assertEquals(2, model.snapshot().lineCount(), "Polling remains independent of follow and selection");
			assertEquals(1, ((VirtualLogList) field(state, "logItems")).size());
			TabPane tabs = (TabPane) field(controller[0], "tabPane");
			tabs.getSelectionModel().select(tab[0]);
			assertEquals(2, ((LogListView) tab[0].getContent()).getItems().size());
			assertFalse(((LogListView) tab[0].getContent()).followingProperty().get());
			tabs.getTabs().remove(tab[0]);
			assertEquals(0, ((TailScheduler) field(controller[0], "tailScheduler")).registrationCount());
			controller[0].dispose();
			assertEquals(0, ((TailScheduler) field(controller[0], "tailScheduler")).queuedJobs());
		});
	}

	@Test
	void inactiveTailPublicationsWaitForActivationAndKeepNewestSnapshot() throws Exception {
		Path path = tempDir.resolve("inactive-tail.log");
		Files.writeString(path, "original");
		LogFileModel model = new LogFileModel(path);
		MainController[] controller = new MainController[1];
		Tab[] tab = new Tab[1];
		AtomicInteger changes = new AtomicInteger();
		fx(() -> {
			controller[0] = openingController();
			tab[0] = readyTailTab(controller[0], model, path);
			VirtualLogList items = (VirtualLogList) field(tab[0].getUserData(), "logItems");
			items.addListener((ListChangeListener<LogLine>) change -> {
				boolean publication = false;
				while (change.next())
					publication |= !change.wasUpdated();
				if (publication)
					changes.incrementAndGet();
			});
			((LogListView) tab[0].getContent()).followingProperty().set(false);
			TabPane tabs = (TabPane) field(controller[0], "tabPane");
			Tab other = readyTailTab(controller[0], model, path);
			tabs.getSelectionModel().select(other);
		});
		for (int i = 0; i < 3; i++) {
			Files.writeString(path, "\nappend " + i, StandardOpenOption.APPEND);
			model.updateIndex();
			fx(() -> {
				deliverTailUpdate(controller[0], tab[0].getUserData(), model.snapshot());
				assertFalse(((java.util.concurrent.atomic.AtomicBoolean)
						field(tab[0].getUserData(), "updateQueued")).get(), "No append callbacks for hidden tabs");
			});
			fx(() -> assertEquals(0, changes.get(), "Hidden lists must not publish append changes"));
		}
		fx(() -> {
			VirtualLogList items = (VirtualLogList) field(tab[0].getUserData(), "logItems");
			assertEquals(1, items.size());
			((TabPane) field(controller[0], "tabPane")).getSelectionModel().select(tab[0]);
			assertEquals(model.snapshot().version(), items.snapshot().version());
			assertEquals(4, items.size());
			assertEquals(1, changes.get(), "Activation publishes only the newest accumulated snapshot");
			assertFalse(((LogListView) tab[0].getContent()).followingProperty().get());
		});
	}

	private static void deliverTailUpdate(MainController controller, Object state, LogFileModel.Snapshot snapshot) {
		invoke(controller, "acceptTailUpdate", new Class<?>[] { state.getClass(), LogFileModel.Snapshot.class },
				state, snapshot);
	}

	@Test
	void inactiveRewriteInvalidatesOccurrencesWithoutPublishingSourceList() throws Exception {
		Path file = tempDir.resolve("inactive-occurrences.log");
		Files.writeString(file, "ERROR old");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		try (var service = new ScanService(1, 8, callbacks::add)) {
			ScanUi ui = scanUi(file, service);
			fx(() -> search(ui));
			workerFence(service);
			fx(() -> drain(callbacks));
			awaitUi(() -> occurrenceView(ui).getItems().getFirst().isAvailable());
			long capturedVersion = ui.model().snapshot().version();
			Files.writeString(file, "replacement");
			ui.model().updateIndex();
			fx(() -> {
				Object state = ui.tabs().getTabs().getFirst().getUserData();
				assertEquals(false, field(state, "active"));
				deliverTailUpdate(ui.controller(), state, ui.model().snapshot());
			});
			fx(() -> {
				assertEquals(capturedVersion, ui.items().snapshot().version());
				assertTrue(occurrenceView(ui).getItems().getFirst().isReadError());
			});
		}
	}

	@Test
	void hiddenRewriteRejectsHeldSearchResultBeforeSourcePublication() throws Exception {
		Path file = tempDir.resolve("inactive-held-search.log");
		Files.writeString(file, "ERROR old");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		try (var service = new ScanService(1, 8, callbacks::add)) {
			ScanUi ui = scanUi(file, service);
			fx(() -> search(ui));
			workerFence(service);
			Files.writeString(file, "replacement");
			ui.model().updateIndex();
			fx(() -> {
				Object state = ui.tabs().getTabs().getFirst().getUserData();
				deliverTailUpdate(ui.controller(), state, ui.model().snapshot());
				drain(callbacks);
				var layout = (javafx.scene.layout.VBox) ui.occurrences().getTabs().getFirst().getContent();
				assertEquals("File changed during search. Click the highlight to search again.",
						((Label) layout.getChildren().getFirst()).getText());
				assertTrue(layout.getChildren().stream().noneMatch(LogListView.class::isInstance));
			});
		}
	}

	@Test
	void queuedTailPublicationCannotUpdateClosedTab() throws Exception {
		Path path = tempDir.resolve("closed-tail.log");
		Files.writeString(path, "old");
		LogFileModel model = new LogFileModel(path);
		MainController[] controller = new MainController[1];
		Tab[] tab = new Tab[1];
		fx(() -> {
			controller[0] = openingController();
			tab[0] = readyTailTab(controller[0], model, path);
		});
		long initial = model.snapshot().version();
		Files.writeString(path, "\nnew", StandardOpenOption.APPEND);
		model.updateIndex();
		fx(() -> {
			Object state = tab[0].getUserData();
			deliverTailUpdate(controller[0], state, model.snapshot());
			((TabPane) field(controller[0], "tabPane")).getTabs().remove(tab[0]);
			deliverTailUpdate(controller[0], state, model.snapshot());
		});
		fx(() -> {
			Object state = tab[0].getUserData();
			assertEquals(initial, ((VirtualLogList) field(state, "logItems")).snapshot().version());
			assertNull(((java.util.concurrent.atomic.AtomicReference<?>) field(state, "pendingUpdate")).get());
		});
	}

	private Tab readyTailTab(MainController controller, LogFileModel model, Path path) {
		try {
			Class<?> type = Class.forName(MainController.class.getName() + "$TabState");
			var constructor = type.getDeclaredConstructors()[0];
			constructor.setAccessible(true);
			LogListView list = new LogListView();
			VirtualLogList items = new VirtualLogList(model);
			list.setItems(items);
			Object state = constructor.newInstance(path.toFile(), "Default", model, items, list);
			Tab tab = new Tab(path.getFileName().toString(), list);
			tab.setUserData(state);
			((TabPane) field(controller, "tabPane")).getTabs().add(tab);
			return tab;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void fileOpenWaitsForOwnerDeliveryAndRetainsFollowSetting() throws Exception {
		Path file = tempDir.resolve("delivered.log");
		Files.writeString(file, "one\ntwo");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		FileOpenService opener = new FileOpenService(callbacks::add);
		MainController[] controller = new MainController[1];
		Tab[] tab = new Tab[1];
		fx(() -> {
			controller[0] = openingController(opener);
			invoke(controller[0], "openFile", new Class<?>[] { java.io.File.class, String.class }, file.toFile(), "Default");
			TabPane tabs = (TabPane) field(controller[0], "tabPane");
			tab[0] = tabs.getTabs().getFirst();
			((LogListView) field(tab[0].getUserData(), "logListView")).followingProperty().set(false);
			invoke(controller[0], "openFile", new Class<?>[] { java.io.File.class, String.class }, file.toFile(), "Default");
			assertEquals(1, tabs.getTabs().size(), "Duplicate loading opens select the existing tab");
		});
		// All three opening events are queued, not published, until we run their callbacks.
		Runnable success = openingCompletion(callbacks);
		fx(() -> {
			assertNull(field(tab[0].getUserData(), "logModel"));
			success.run();
			LogListView list = assertInstanceOf(LogListView.class, tab[0].getContent());
			assertFalse(list.followingProperty().get());
			assertEquals(2, list.getItems().size());
			assertNotNull(field(tab[0].getUserData(), "tailTimer"));
			assertEquals(0, list.getSelectionModel().getSelectedIndices().size());
		});
	}

	@Test
	void cancelledOpenCannotAttachLateSuccessAndCanBeRetried() throws Exception {
		Path file = tempDir.resolve("cancelled-open.log");
		Files.writeString(file, "one");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		FileOpenService opener = new FileOpenService(callbacks::add);
		MainController[] controller = new MainController[1];
		Tab[] tab = new Tab[1];
		fx(() -> {
			controller[0] = openingController(opener);
			invoke(controller[0], "openFile", new Class<?>[] { java.io.File.class, String.class }, file.toFile(), "Default");
			tab[0] = ((TabPane) field(controller[0], "tabPane")).getTabs().getFirst();
		});
		Runnable success = openingCompletion(callbacks);
		fx(() -> {
			openingAction(tab[0]).fire();
			success.run();
			assertNull(field(tab[0].getUserData(), "logModel"));
		});
		Runnable cancelled = callbacks.poll(10, TimeUnit.SECONDS);
		assertNotNull(cancelled);
		fx(() -> {
			cancelled.run();
			assertTrue(openingMessage(tab[0]).contains("cancelled"));
			assertNull(field(tab[0].getUserData(), "tailTimer"));
			assertEquals("Retry opening", openingAction(tab[0]).getText());
			openingAction(tab[0]).fire();
		});
		Runnable retry = openingCompletion(callbacks);
		fx(() -> {
			retry.run();
			assertInstanceOf(LogListView.class, tab[0].getContent());
			assertEquals(1, ((TabPane) field(controller[0], "tabPane")).getTabs().size());
		});
	}

	@Test
	void closingOrDisposingLoadingTabSuppressesPendingAttachment() throws Exception {
		for (boolean shutdown : new boolean[] { false, true }) {
			Path file = tempDir.resolve("closed-open-" + shutdown + ".log");
			Files.writeString(file, "one");
			var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
			FileOpenService opener = new FileOpenService(callbacks::add);
			MainController[] controller = new MainController[1];
			Tab[] tab = new Tab[1];
			fx(() -> {
				controller[0] = openingController(opener);
				invoke(controller[0], "openFile", new Class<?>[] { java.io.File.class, String.class }, file.toFile(), "Default");
				tab[0] = ((TabPane) field(controller[0], "tabPane")).getTabs().getFirst();
			});
			Runnable success = openingCompletion(callbacks);
			fx(() -> {
				if (shutdown)
					controller[0].dispose();
				else
					invoke(controller[0], "closeTab", new Class<?>[] { Tab.class, boolean.class }, tab[0], true);
				success.run();
				drain(callbacks);
				assertEquals(true, field(tab[0].getUserData(), "closed"));
				assertNull(field(tab[0].getUserData(), "logModel"));
				assertNull(field(tab[0].getUserData(), "logItems"));
				assertNull(field(tab[0].getUserData(), "tailTimer"));
			});
		}
	}

	@Test
	void failedOpenShowsInlineErrorAndRetryLoadsReplacement() throws Exception {
		Path file = tempDir.resolve("missing-open.log");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		FileOpenService opener = new FileOpenService(callbacks::add);
		MainController[] controller = new MainController[1];
		Tab[] tab = new Tab[1];
		fx(() -> {
			controller[0] = openingController(opener);
			invoke(controller[0], "openFile", new Class<?>[] { java.io.File.class, String.class }, file.toFile(), "Default");
			tab[0] = ((TabPane) field(controller[0], "tabPane")).getTabs().getFirst();
		});
		Runnable failure = openingCompletion(callbacks);
		fx(() -> {
			failure.run();
			assertTrue(openingMessage(tab[0]).startsWith("Could not open file:"));
			assertNull(field(tab[0].getUserData(), "logModel"));
			assertNull(field(tab[0].getUserData(), "tailTimer"));
		});
		Files.writeString(file, "replacement");
		fx(() -> openingAction(tab[0]).fire());
		Runnable retry = openingCompletion(callbacks);
		fx(() -> {
			retry.run();
			assertInstanceOf(LogListView.class, tab[0].getContent());
		});
	}

	private static javafx.scene.control.Button openingAction(Tab tab) {
		return (javafx.scene.control.Button) ((javafx.scene.layout.VBox) tab.getContent()).getChildren().getLast();
	}

	@Test
	void queuedOpeningLeavesFxResponsiveAndOverloadIsVisible() throws Exception {
		Path first = tempDir.resolve("queued-open.log");
		Path second = tempDir.resolve("rejected-open.log");
		Files.writeString(first, "one");
		Files.writeString(second, "two");
		var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		FileOpenService opener = new FileOpenService(1, 1, callbacks::add);
		ScanService workers = (ScanService) field(opener, "service");
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		workers.submit(new Object(), _ -> {
			entered.countDown();
			assertTrue(release.await(10, TimeUnit.SECONDS));
			return null;
		}, (_, _) -> { });
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		MainController[] controller = new MainController[1];
		try {
			fx(() -> {
				controller[0] = openingController(opener);
				invoke(controller[0], "openFile", new Class<?>[] { java.io.File.class, String.class }, first.toFile(), "Default");
				invoke(controller[0], "openFile", new Class<?>[] { java.io.File.class, String.class }, second.toFile(), "Default");
				drain(callbacks);
				TabPane tabs = (TabPane) field(controller[0], "tabPane");
				assertTrue(openingMessage(tabs.getTabs().getFirst()).contains("queued"));
				assertTrue(openingMessage(tabs.getTabs().getLast()).contains("queue is full"));
				assertEquals("Retry opening", openingAction(tabs.getTabs().getLast()).getText());
				assertEquals(1, opener.queuedJobs());
			});
			pulses(); // An FX animation completes while the only opening worker is still blocked.
			fx(() -> {
				assertEquals(1, opener.activeJobs());
				TabPane tabs = (TabPane) field(controller[0], "tabPane");
				invoke(controller[0], "closeTab", new Class<?>[] { Tab.class, boolean.class }, tabs.getTabs().getFirst(), true);
				assertEquals(0, opener.queuedJobs(), "Closing removes queued indexing work immediately");
			});
		} finally {
			release.countDown();
			opener.close();
		}
	}

	private static String openingMessage(Tab tab) {
		return ((Label) ((javafx.scene.layout.VBox) tab.getContent()).getChildren().getFirst()).getText();
	}

	private static Runnable openingCompletion(java.util.concurrent.BlockingQueue<Runnable> callbacks) throws Exception {
		// ScanService guarantees ordered QUEUED, RUNNING, terminal events for a non-cancelled open.
		for (int event = 0; event < 2; event++) {
			Runnable callback = callbacks.poll(10, TimeUnit.SECONDS);
			assertNotNull(callback);
			fx(callback);
		}
		Runnable terminal = callbacks.poll(10, TimeUnit.SECONDS);
		assertNotNull(terminal);
		return terminal;
	}

	private static Object field(Object target, String name) {
		try {
			var field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(target);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
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