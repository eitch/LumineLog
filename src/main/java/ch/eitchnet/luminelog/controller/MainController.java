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

import ch.eitchnet.luminelog.LumineLogApplication;
import ch.eitchnet.luminelog.model.*;
import ch.eitchnet.luminelog.service.ScanService;
import ch.eitchnet.luminelog.service.LineLoadingService;
import ch.eitchnet.luminelog.util.DialogUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class MainController {

	private static final Logger logger = LoggerFactory.getLogger(MainController.class);

	private final ConfigService configService = new ConfigService();
	private final ScanService scanService;
	private final LineLoadingService lineLoadingService = new LineLoadingService(Platform::runLater);
	private final Object highlightCountKey = new Object();
	private boolean disposed;

	public MainController() {
		this(new ScanService(Platform::runLater));
	}

	MainController(ScanService scanService) {
		this.scanService = scanService;
	}

	@FXML
	private VBox rootContainer;
	@FXML
	private TabPane tabPane;
	@FXML
	private TabPane occurrencesTabPane;
	@FXML
	private SplitPane mainSplitPane;
	@FXML
	private TextField searchField;
	@FXML
	private CheckBox regexCheckBox;
	@FXML
	private ColorPicker highlightColorPicker;
	@FXML
	private Label statusLabel;
	@FXML
	private CheckBox tailCheckBox;
	@FXML
	private Spinner<Integer> fontSizeSpinner;
	@FXML
	private ComboBox<String> highlightGroupComboBox;
	@FXML
	private javafx.scene.layout.FlowPane highlightsPane;
	@FXML
	private Menu historyMenu;

	private boolean updatingTailCheckBox;
	private String currentGroup = null;
	private final List<HighlightRule> highlightRules = new ArrayList<>();
	private final java.util.Map<TabState, java.util.Map<HighlightRule, Tab>> openOccurrenceTabs
			= new java.util.HashMap<>();

	private static class TabState {
		File file;
		String highlightGroup;
		LogFileModel logModel;
		VirtualLogList logItems;
		Timer tailTimer;
		LogListView logListView;
		int[] highlightCounts;
		int lastCountedLine = 0;
		volatile boolean closed;
		final AtomicReference<LogFileModel.Snapshot> pendingUpdate = new AtomicReference<>();
		final AtomicBoolean updateQueued = new AtomicBoolean();
		List<HighlightRule> countedRules = List.of();
		long countedGeneration = -1;
		int[] countsBeforeLastLine;

		TabState(File file, String highlightGroup, LogFileModel logModel, VirtualLogList logItems,
				LogListView logListView) {
			this.file = file;
			this.highlightGroup = highlightGroup;
			this.logModel = logModel;
			this.logItems = logItems;
			this.logListView = logListView;
		}

		void stopTailing() {
			closed = true;
			pendingUpdate.set(null);
			logItems.close();
			logListView.dispose();
			if (tailTimer != null) {
				tailTimer.cancel();
				tailTimer = null;
			}
		}
	}

	@FXML
	public void initialize() {
		LumineLogApplication
				.getPrimaryStage()
				.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, this::handleWindowCloseRequest);

		Config config = this.configService.loadConfig();
		this.fontSizeSpinner.setValueFactory(
				new SpinnerValueFactory.IntegerSpinnerValueFactory(8, 32, config.getFontSize()));
		this.fontSizeSpinner.valueProperty().addListener((_, _, _) -> handleFontResize());

		this.highlightColorPicker.setValue(Color.valueOf("#ff8080"));
		loadHighlights(true);
		updateHistoryMenu();
		this.highlightGroupComboBox.getSelectionModel().select(currentGroup);

		this.occurrencesTabPane
				.getTabs()
				.addListener(this::handleOcurrencesTabChanged);
		this.mainSplitPane.setDividerPositions(1.0);

		this.tabPane
				.getSelectionModel()
				.selectedItemProperty()
				.addListener((_, oldTab, newTab) -> handleActiveTabChanged(oldTab, newTab));
		this.tabPane.getTabs().addListener(this::handleRemovedTabs);

		this.tailCheckBox.selectedProperty().addListener((_, _, isSelected) -> handleTailCheckBoxChanged(isSelected));

		Platform.runLater(this::loadLastFiles);
		setupDragAndDrop();
	}

	private void handleRemovedTabs(ListChangeListener.Change<? extends Tab> change) {
		while (change.next()) {
			if (!change.wasRemoved())
				continue;

			for (Tab tab : change.getRemoved()) {
				TabState state = (TabState) tab.getUserData();
				if (state == null)
					continue;
				state.stopTailing();
				if (countingState == state)
					cancelHighlightCount();
				Map<HighlightRule, Tab> ruleTabMap = this.openOccurrenceTabs.remove(state);
				if (ruleTabMap != null) {
					for (Tab occurrence : ruleTabMap.values())
						cancelOccurrenceSearch(occurrence);
					this.occurrencesTabPane.getTabs().removeAll(ruleTabMap.values());
				}
			}
		}
	}

	private void handleTailCheckBoxChanged(Boolean isSelected) {
		if (updatingTailCheckBox)
			return;
		TabState state = getActiveTabState();
		if (state == null)
			return;
		state.logListView.followingProperty().set(isSelected);
	}

	private void handleActiveTabChanged(Tab oldTab, Tab newTab) {
		if (oldTab != null && !this.ignoreGroupChange)
			saveHighlights();

		if (newTab == null) {
			this.statusLabel.setText("No file opened");
		} else {
			TabState state = (TabState) newTab.getUserData();
			updatingTailCheckBox = true;
			try {
				tailCheckBox.setSelected(state.logListView.followingProperty().get());
			} finally {
				updatingTailCheckBox = false;
			}
			state.logListView.requestTail();
			updateFooterStatus(state);

			if (state.highlightGroup != null && !state.highlightGroup.equals(this.currentGroup)) {
				this.currentGroup = state.highlightGroup;
				loadHighlights(false);
				this.highlightGroupComboBox.getSelectionModel().select(this.currentGroup);
			}
			state.logListView.refresh();
		}

		updateHighlightsBar();
	}

	private void handleOcurrencesTabChanged(ListChangeListener.Change<? extends Tab> change) {
		while (change.next()) {
			for (Tab tab : change.getRemoved()) {
				cancelOccurrenceSearch(tab);
				for (var tabs : openOccurrenceTabs.values())
					tabs.values().removeIf(value -> value == tab);
				openOccurrenceTabs.values().removeIf(Map::isEmpty);
			}
		}
		if (this.occurrencesTabPane.getTabs().isEmpty()) {
			this.mainSplitPane.setDividerPositions(1.0);
		} else if (this.mainSplitPane.getDividerPositions()[0] > 0.95) {
			this.mainSplitPane.setDividerPositions(0.7);
		}
	}

	private void handleFontResize() {
		saveHighlights();
		for (Tab tab : tabPane.getTabs()) {
			TabState state = (TabState) tab.getUserData();
			configureRowHeight(state.logListView);
			state.logListView.refresh();
			state.logListView.requestTail();
		}
		for (Tab tab : occurrencesTabPane.getTabs()) {
			if (tab.getContent() instanceof VBox box) {
				for (Node node : box.getChildren()) {
					if (node instanceof ListView<?> list) {
						configureRowHeight(list);
						list.refresh();
					}
				}
			}
		}
	}

	private void handleWindowCloseRequest(WindowEvent e) {
		if (!this.handleExit())
			e.consume();
	}

	private void setupDragAndDrop() {
		this.rootContainer.setOnDragOver(e -> {
			if (e.getGestureSource() != this.rootContainer && e.getDragboard().hasFiles()) {
				e.acceptTransferModes(TransferMode.COPY_OR_MOVE);
			}
			e.consume();
		});

		this.rootContainer.setOnDragDropped(e -> {
			Dragboard db = e.getDragboard();
			boolean success = false;
			if (db.hasFiles()) {
				for (File file : db.getFiles()) {
					openFile(file, currentGroup);
				}
				success = true;
			}
			e.setDropCompleted(success);
			e.consume();
		});
	}

	private void loadLastFiles() {
		Config config = configService.loadConfig();
		List<OpenFileInfo> openFiles = config.getOpenFiles();
		if (openFiles.isEmpty())
			return;

		for (OpenFileInfo openFileInfo : openFiles) {
			File file = new File(openFileInfo.filePath());
			if (file.exists())
				openFile(file, openFileInfo.highlightGroup());
		}
	}

	private void saveHighlights() {
		Config currentConfig = this.configService.loadConfig();

		List<HighlightGroup> groups = new ArrayList<>();
		for (String groupName : this.highlightGroupComboBox.getItems()) {
			List<HighlightRule> rules;
			if (groupName.equals(this.currentGroup)) {
				rules = new ArrayList<>(this.highlightRules);
			} else {
				// Keep existing rules for other groups
				rules = currentConfig
						.getHighlightGroups()
						.stream()
						.filter(g -> g.getName().equals(groupName))
						.findFirst()
						.map(HighlightGroup::getRules)
						.orElse(new ArrayList<>());
			}
			groups.add(new HighlightGroup(groupName, rules));
		}

		List<OpenFileInfo> openFiles = this.tabPane
				.getTabs()
				.stream()
				.map(t -> (TabState) t.getUserData())
				.map(s -> new OpenFileInfo(s.file.getAbsolutePath(), s.highlightGroup))
				.toList();

		Config newConfig = new Config(openFiles, currentConfig.getHistory(), this.currentGroup,
				this.fontSizeSpinner.getValue(), groups);

		this.configService.saveConfig(newConfig);
	}

	private void updateHistoryMenu() {
		Config config = this.configService.loadConfig();
		List<String> history = config.getHistory();
		this.historyMenu.getItems().clear();
		for (String filePath : history) {
			MenuItem menuItem = new MenuItem(filePath);
			menuItem.setOnAction(_ -> handleHistoryMenuItemSelected(filePath));
			this.historyMenu.getItems().add(menuItem);
		}
	}

	private void handleHistoryMenuItemSelected(String filePath) {
		File file = new File(filePath);
		if (file.exists()) {
			openFile(file, this.currentGroup);
			updateHistoryMenu();
		} else {
			DialogUtil.showError("The file " + filePath + " does not exist anymore.");
			removeFromHistory(filePath);
		}
	}

	private void removeFromHistory(String filePath) {
		Config config = this.configService.loadConfig();
		List<String> history = new ArrayList<>(config.getHistory());
		if (history.remove(filePath)) {
			config.setHistory(history);
			this.configService.saveConfig(config);
			updateHistoryMenu();
		}
	}

	private void addToHistory(File file) {
		String filePath = file.getAbsolutePath();
		Config config = this.configService.loadConfig();
		List<String> history = new ArrayList<>(config.getHistory());
		history.remove(filePath);
		history.addFirst(filePath);
		if (history.size() > 16) {
			history = history.subList(0, 16);
		}
		config.setHistory(history);
		this.configService.saveConfig(config);
		updateHistoryMenu();
	}

	private boolean ignoreGroupChange = false;

	private void loadHighlights(boolean updateGroups) {
		this.ignoreGroupChange = true;
		try {
			Config config = this.configService.loadConfig();
			logger.info("Loading highlights from JSON config");

			if (this.currentGroup == null || this.currentGroup.isEmpty()) {
				this.currentGroup = config.getLastGroup();
				if (this.currentGroup == null)
					this.currentGroup = "Default";
			}

			if (updateGroups) {
				List<String> groupNames = config
						.getHighlightGroups()
						.stream()
						.map(HighlightGroup::getName)
						.collect(Collectors.toList());

				if (groupNames.isEmpty()) {
					groupNames.add("Default");
					this.currentGroup = "Default";
				}

				this.highlightGroupComboBox.getItems().setAll(groupNames);
				this.highlightGroupComboBox.getSelectionModel().select(this.currentGroup);
			}

			this.highlightRules.clear();
			config
					.getHighlightGroups()
					.stream()
					.filter(g -> g.getName().equals(this.currentGroup))
					.findFirst()
					.ifPresent(g -> this.highlightRules.addAll(g.getRules()));

			updateHighlightsBar();
		} finally {
			this.ignoreGroupChange = false;
		}
	}

	@FXML
	private void handleGroupChange() {
		if (this.ignoreGroupChange)
			return;

		String newGroup = this.highlightGroupComboBox.getValue();
		if (newGroup == null || newGroup.isEmpty() || newGroup.equals(this.currentGroup))
			return;

		saveHighlights();
		this.currentGroup = newGroup;
		if (!this.highlightGroupComboBox.getItems().contains(this.currentGroup))
			this.highlightGroupComboBox.getItems().add(this.currentGroup);

		TabState state = getActiveTabState();
		if (state != null)
			state.highlightGroup = this.currentGroup;

		loadHighlights(false);

		if (state != null)
			state.logListView.refresh();
	}

	@FXML
	private void handleAddGroup() {
		TextInputDialog dialog = new TextInputDialog("New Group");
		dialog.setTitle("New Highlight Group");
		dialog.setHeaderText("Create a new highlight group");
		dialog.setContentText("Please enter the name of the new group:");
		dialog.showAndWait().ifPresent(this::createNewGroup);
	}

	@FXML
	private void handleRenameGroup() {
		String oldGroup = this.highlightGroupComboBox.getValue();
		if (oldGroup == null || oldGroup.equals("Default")) {
			DialogUtil.showError("The 'Default' group cannot be renamed.");
			return;
		}

		TextInputDialog dialog = new TextInputDialog(oldGroup);
		dialog.setTitle("Rename Highlight Group");
		dialog.setHeaderText("Rename highlight group '" + oldGroup + "'");
		dialog.setContentText("Please enter the new name for the group:");
		dialog.showAndWait().ifPresent(name -> renameGroup(name, oldGroup));
	}

	@FXML
	private void handleDeleteGroup() {
		String groupToDelete = highlightGroupComboBox.getValue();
		if (groupToDelete == null || groupToDelete.equals("Default")) {
			return;
		}

		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.setTitle("Delete Group");
		alert.setHeaderText("Delete highlight group '" + groupToDelete + "'?");
		alert.setContentText("This will remove all highlights in this group.");
		if (alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK)
			deleteGroup(groupToDelete);
	}

	private void createNewGroup(String name) {
		String newGroup = name.trim();
		if (newGroup.isEmpty()) {
			DialogUtil.showError("Group name cannot be empty");
			return;
		}

		if (this.highlightGroupComboBox.getItems().contains(newGroup)) {
			DialogUtil.showError("Group '" + newGroup + "' already exists");
			return;
		}

		saveHighlights();
		this.ignoreGroupChange = true;
		try {
			this.currentGroup = newGroup;
			this.highlightGroupComboBox.getItems().add(newGroup);
			this.highlightGroupComboBox.getSelectionModel().select(newGroup);
			this.highlightRules.clear();
			updateHighlightsBar();
			saveHighlights();
		} finally {
			this.ignoreGroupChange = false;
		}
	}

	private void renameGroup(String name, String oldGroup) {
		String newGroup = name.trim();
		if (newGroup.isEmpty()) {
			DialogUtil.showError("Group name cannot be empty");
			return;
		}
		if (newGroup.equals(oldGroup)) {
			return;
		}
		if (highlightGroupComboBox.getItems().contains(newGroup)) {
			DialogUtil.showError("Group '" + newGroup + "' already exists");
			return;
		}

		ignoreGroupChange = true;
		try {
			int index = highlightGroupComboBox.getItems().indexOf(oldGroup);
			highlightGroupComboBox.getItems().set(index, newGroup);
			highlightGroupComboBox.getSelectionModel().select(newGroup);
			currentGroup = newGroup;
			saveHighlights();
		} finally {
			ignoreGroupChange = false;
		}
	}

	private void deleteGroup(String groupToDelete) {
		ignoreGroupChange = true;
		try {
			highlightGroupComboBox.getItems().remove(groupToDelete);
			currentGroup = "Default";
			highlightGroupComboBox.getSelectionModel().select(currentGroup);
			saveHighlights();
		} finally {
			ignoreGroupChange = false;
		}
	}

	private void closeTab(Tab tab, boolean noConfirm) {
		TabState state = (TabState) tab.getUserData();
		if (state == null)
			return;

		if (noConfirm || DialogUtil.showConfirmation("Close File", "Are you sure you want to close this file?",
				state.file.getAbsolutePath())) {
			state.stopTailing();
			tabPane.getTabs().remove(tab);
			saveHighlights();
		}
	}

	@FXML
	private void handleTabClose() {
		Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
		if (selectedTab != null)
			closeTab(selectedTab, false);
	}

	@FXML
	private void handleFileOpen() {
		FileChooser fileChooser = new FileChooser();
		fileChooser.setTitle("Open Log File");
		File file = fileChooser.showOpenDialog(tabPane.getScene().getWindow());
		if (file != null)
			openFile(file, currentGroup);
	}

	private void openFile(File file, String highlightGroup) {
		addToHistory(file);
		for (Tab existingTab : tabPane.getTabs()) {
			TabState state = (TabState) existingTab.getUserData();
			if (state != null && state.file.equals(file)) {
				tabPane.getSelectionModel().select(existingTab);
				return;
			}
		}

		try {
			LogFileModel logModel = new LogFileModel(file.toPath());
			VirtualLogList logItems = new VirtualLogList(logModel, lineLoadingService);
			LogListView logListView = createLogListView();
			logListView.setItems(logItems);

			Tab tab = new Tab();
			Label tabLabel = new Label(file.getName());
			Button closeBtn = new Button("×");
			closeBtn.setStyle("-fx-background-color: transparent; -fx-padding: 0 0 0 5; -fx-cursor: hand;");
			TabState state = new TabState(file, highlightGroup, logModel, logItems, logListView);
			logListView.followingProperty().addListener((_, _, following) -> {
				if (getActiveTabState() == state) {
					updatingTailCheckBox = true;
					try {
						tailCheckBox.setSelected(following);
					} finally {
						updatingTailCheckBox = false;
					}
				}
			});
			closeBtn.setOnAction(_ -> closeTab(tab, false));
			HBox graphic = new HBox(tabLabel, closeBtn);
			graphic.setAlignment(Pos.CENTER);
			tab.setGraphic(graphic);
			tab.setClosable(false);
			tab.setContent(logListView);

			graphic.setOnMouseReleased(event -> {
				if (event.getButton() == MouseButton.MIDDLE)
					closeTab(tab, true);
			});
			tab.setUserData(state);
			tab.setOnClosed(_ -> {
				logger.info("Closing tab for file {}", file.getAbsolutePath());
				state.stopTailing();
			});

			tabPane.getTabs().add(tab);
			tabPane.getSelectionModel().select(tab);

			updateHighlightsBar();
			logListView.requestTail();
			startTailing(state);
		} catch (IOException e) {
			DialogUtil.showError("Could not open file: " + e.getMessage());
		}
	}

	public static double toPrecision(double value, int decimals) {
		if (value == 0.0)
			return 0.0;
		if (Double.isNaN(value))
			return Double.NaN;
		if (value == Double.NEGATIVE_INFINITY)
			return Double.NEGATIVE_INFINITY;
		if (value == Double.POSITIVE_INFINITY)
			return Double.POSITIVE_INFINITY;
		return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_EVEN).doubleValue();
	}

	private void copyToClipboard(String text) {
		if (text == null || text.isEmpty())
			return;
		Clipboard clipboard = Clipboard.getSystemClipboard();
		ClipboardContent content = new ClipboardContent();
		content.putString(text);
		clipboard.setContent(content);
	}

	static String selectedText(List<LogLine> selected) {
		for (LogLine line : selected) {
			if (!line.isAvailable())
				throw new IllegalStateException("Cannot copy selection: line " + line.lineNumber()
						+ (line.isLoading() ? " is still loading." : " is unavailable. " + line.errorMessage()));
		}
		return selected.stream().map(LogLine::content).collect(Collectors.joining(System.lineSeparator()));
	}

	private void copySelectedLines(ListView<LogLine> listView) {
		List<LogLine> selected = listView.getSelectionModel().getSelectedItems();
		if (selected == null || selected.isEmpty())
			return;
		try {
			copyToClipboard(selectedText(selected));
		} catch (IllegalStateException e) {
			DialogUtil.showError(e.getMessage());
		}
	}

	private void setupCopyContextMenu(ListView<LogLine> listView) {
		ContextMenu contextMenu = new ContextMenu();
		MenuItem copyItem = new MenuItem("Copy Selected Lines");
		copyItem.setOnAction(_ -> copySelectedLines(listView));
		contextMenu.getItems().add(copyItem);
		MenuItem retryItem = new MenuItem("Retry unavailable lines");
		retryItem.setOnAction(_ -> {
			if (listView.getItems() instanceof VirtualLogList items)
				items.retryUnavailable();
		});
		contextMenu.getItems().add(retryItem);

		listView.setContextMenu(contextMenu);

		// Optional: Keyboard shortcut for copy
		listView.setOnKeyPressed(e -> {
			if (!e.isShortcutDown() || e.getCode() != KeyCode.C)
				return;
			copySelectedLines(listView);
			e.consume();
		});
	}

	private LogListView createLogListView() {
		LogListView logListView = new LogListView();
		logListView.setCellFactory(getHighlightingCellCallback());
		configureRowHeight(logListView);
		setupCopyContextMenu(logListView);
		return logListView;
	}

	private void configureRowHeight(ListView<?> listView) {
		Text sample = new Text("Ag");
		sample.setFont(javafx.scene.text.Font.font("monospace", fontSizeSpinner.getValue()));
		listView.setFixedCellSize(Math.ceil(sample.getLayoutBounds().getHeight()) + 4);
	}

	private record MatchWithColor(int start, int end, String color) {
	}

	private TabState getActiveTabState() {
		Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
		if (selectedTab != null) {
			return (TabState) selectedTab.getUserData();
		}
		return null;
	}

	private void startTailing(TabState state) {
		state.tailTimer = new Timer(true);
		state.tailTimer.schedule(new TimerTask() {
			private long lastVersion = state.logItems.snapshot().version();
			private boolean failed;

			@Override
			public void run() {
				if (!state.closed) {
					try {
						state.logModel.updateIndex();
						failed = false;
						LogFileModel.Snapshot update = state.logModel.snapshot();
						if (update.version() != lastVersion) {
							lastVersion = update.version();
							state.pendingUpdate.set(update);
							queuePublication(state);
						}
					} catch (IOException e) {
						if (!failed)
							logger.warn("Could not update log file {}", state.file, e);
						failed = true;
					}
				}
			}
		}, 50, 50);
	}

	private void queuePublication(TabState state) {
		if (state.closed || !state.updateQueued.compareAndSet(false, true))
			return;
		Platform.runLater(() -> {
			LogFileModel.Snapshot update = state.pendingUpdate.getAndSet(null);
			state.updateQueued.set(false);
			if (state.closed || update == null)
				return;
			if (state.logItems.snapshot().generation() != update.generation()) {
				state.logListView.cancelSelectionGesture();
				state.logListView.getSelectionModel().clearSelection();
			}
			state.logItems.publish(update);
			state.logListView.requestTail();
			if (getActiveTabState() == state) {
				updateHighlightsBar();
				updateFooterStatus(state);
			}
			if (state.pendingUpdate.get() != null)
				queuePublication(state);
		});
	}

	@FXML
	private void handleAddHighlight() {
		String pattern = searchField.getText();
		if (pattern != null && !pattern.isEmpty()) {
			Color color = highlightColorPicker.getValue();
			String webColor = toWebColor(color);
			highlightRules.add(new HighlightRule(pattern, webColor, regexCheckBox.isSelected()));
			saveHighlights();

			TabState state = getActiveTabState();
			if (state != null) {
				state.highlightCounts = null;
				state.lastCountedLine = 0;
				state.logListView.refresh();
			}
			updateHighlightsBar();
		}
	}

	private long lastUpdateHighlightsBar = 0;
	private boolean countPending;
	private final javafx.animation.PauseTransition highlightsDelay
			= new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
	private TabState countingState;
	private List<HighlightRule> countingRules = List.of();
	private long countingGeneration = -1;

	private void cancelHighlightCount() {
		countPending = false;
		scanService.cancel(highlightCountKey);
	}

	private record HighlightCounts(int[] counts, int[] beforeLast) {
	}

	private void updateHighlightsBar() {
		if (disposed)
			return;
		TabState state = getActiveTabState();
		if (state == null || state.logModel == null || highlightRules.isEmpty()) {
			highlightsDelay.stop();
			cancelHighlightCount();
			highlightsPane.getChildren().clear();
			if (state != null) {
				state.highlightCounts = null;
				state.lastCountedLine = 0;
			}
			return;
		}

		List<HighlightRule> currentRules = List.copyOf(highlightRules);
		LogFileModel.Snapshot snapshot = state.logItems.snapshot();
		boolean sameJob = countingState == state && countingRules.equals(currentRules)
				&& countingGeneration == snapshot.generation();
		if (countPending && sameJob)
			return;
		cancelHighlightCount();
		long now = System.currentTimeMillis();
		if (sameJob && now - lastUpdateHighlightsBar < 500) {
			highlightsDelay.setDuration(javafx.util.Duration.millis(500 - (now - lastUpdateHighlightsBar)));
			highlightsDelay.setOnFinished(_ -> updateHighlightsBar());
			highlightsDelay.playFromStart();
			return;
		}
		highlightsDelay.stop();
		lastUpdateHighlightsBar = now;
		countingState = state;
		countingRules = currentRules;
		countingGeneration = snapshot.generation();
		boolean incremental = state.highlightCounts != null && state.countsBeforeLastLine != null
				&& state.countedRules.equals(currentRules) && state.countedGeneration == snapshot.generation();
		int startLine = incremental ? Math.max(0, state.lastCountedLine - 1) : 0;
		int[] counts = incremental ? state.countsBeforeLastLine.clone() : new int[currentRules.size()];
		List<HighlightRule> workerRules = currentRules.stream()
				.map(rule -> new HighlightRule(rule.getPattern(), rule.getColor(), rule.isIsRegex())).toList();
		countPending = true;
		scanService.<HighlightCounts>submit(highlightCountKey, job -> {
			int[] beforeLast = counts.clone();
			int index = startLine;
			while (index < snapshot.lineCount()) {
				if (job.isCancelled() || state.closed)
					return null;
				for (String line : snapshot.readBatch(index).getLines()) {
					if (index == snapshot.lineCount() - 1)
						beforeLast = counts.clone();
					for (int j = 0; j < workerRules.size(); j++) {
						if (job.isCancelled() || state.closed)
							return null;
						counts[j] += workerRules.get(j).countOccurrences(line);
					}
					index++;
				}
			}
			return new HighlightCounts(counts, beforeLast);
		}, (job, event) -> {
			if (disposed || state.closed || getActiveTabState() != state || !highlightRules.equals(currentRules)
					|| state.logItems.snapshot().generation() != snapshot.generation())
				return;
			switch (event.state()) {
			case QUEUED -> highlightsPane.getChildren().setAll(new Label("Highlight count queued..."));
			case RUNNING -> highlightsPane.getChildren().setAll(new Label("Counting highlights..."));
			case SUCCEEDED -> {
				countPending = false;
				state.highlightCounts = event.result().counts();
				state.countsBeforeLastLine = event.result().beforeLast();
				state.lastCountedLine = snapshot.lineCount();
				state.countedRules = currentRules;
				state.countedGeneration = snapshot.generation();
				updateHighlightsUI(currentRules, state.highlightCounts);
				if (snapshot.version() != state.logItems.snapshot().version())
					updateHighlightsBar();
			}
			case CANCELLED, FAILED, REJECTED -> {
				countPending = false;
				state.highlightCounts = null;
				state.countsBeforeLastLine = null;
				state.lastCountedLine = 0;
				String message = switch (event.state()) {
				case CANCELLED -> "Highlight count cancelled";
				case REJECTED -> "Scan capacity reached; highlight counts unavailable. Try again shortly.";
				default -> "Highlight counts unavailable: " + event.error().getMessage();
				};
				if (event.error() != null)
					logger.debug("Could not count highlights in {}", state.file, event.error());
				Label error = new Label(message);
				error.setTooltip(new Tooltip(message));
				updateHighlightsUI(currentRules, null);
				highlightsPane.getChildren().add(error);
			}
			}
		});
	}

	private void updateHighlightsUI(List<HighlightRule> currentRules, int[] counts) {
		highlightsPane.getChildren().clear();
		for (int i = 0; i < currentRules.size(); i++) {
			HighlightRule rule = currentRules.get(i);
			String count = counts == null ? "unavailable" : Integer.toString(counts[i]);

			HBox highlightTag = new HBox(5);
			highlightTag.setAlignment(Pos.CENTER_LEFT);
			String baseStyle = "-fx-background-color: "
					+ rule.getColor()
					+ "; -fx-padding: 8 12 8 12; -fx-background-radius: 4; -fx-cursor: hand; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.2), 3, 0, 0, 1);";
			highlightTag.setStyle(baseStyle);

			Label label = new Label(rule.getPattern() + " (" + count + ")");
			label.setStyle("-fx-text-fill: black; -fx-font-weight: bold;");
			HBox.setHgrow(label, javafx.scene.layout.Priority.ALWAYS);

			Button dismissBtn = new Button("×");
			dismissBtn.setStyle(
					"-fx-background-color: rgba(255,255,255,0.3); -fx-background-radius: 4; -fx-padding: 0 4 0 4; -fx-font-weight: bold; -fx-text-fill: black;");
			dismissBtn.setTooltip(new Tooltip("Remove highlight"));
			HBox.setMargin(dismissBtn, new javafx.geometry.Insets(0, -4, 0, 0));

			dismissBtn.setOnMouseEntered(_ -> dismissBtn.setStyle(
					"-fx-background-color: rgba(255,255,255,0.6); -fx-background-radius: 4; -fx-padding: 0 4 0 4; -fx-font-weight: bold; -fx-text-fill: black;"));
			dismissBtn.setOnMouseExited(_ -> dismissBtn.setStyle(
					"-fx-background-color: rgba(255,255,255,0.3); -fx-background-radius: 4; -fx-padding: 0 4 0 4; -fx-font-weight: bold; -fx-text-fill: black;"));
			dismissBtn.setOnAction(_ -> {
				highlightRules.remove(rule);
				saveHighlights();
				TabState currentState = getActiveTabState();
				if (currentState != null) {
					currentState.highlightCounts = null;
					currentState.lastCountedLine = 0;
					currentState.logListView.refresh();
				}
				updateHighlightsBar();
			});

			highlightTag.getChildren().addAll(label, dismissBtn);

			highlightTag.setOnMouseEntered(_ -> highlightTag.setStyle(baseStyle + "-fx-brightness: 1.1;"));
			highlightTag.setOnMouseExited(_ -> highlightTag.setStyle(baseStyle));

			highlightTag.setOnMouseClicked(event -> {
				if (event.getTarget() == dismissBtn)
					return;
				showOccurrences(rule);
			});
			Tooltip.install(highlightTag, new Tooltip("Click to show all occurrences"));

			highlightsPane.getChildren().add(highlightTag);
		}
	}

	private void showOccurrences(HighlightRule rule) {
		TabState state = getActiveTabState();
		if (disposed || state == null || state.closed || state.logModel == null)
			return;

		java.util.Map<HighlightRule, Tab> ruleTabMap = openOccurrenceTabs.computeIfAbsent(state,
				_ -> new java.util.HashMap<>());
		Tab occurrenceTab;
		VBox layout;
		Label header;
		ProgressBar progressBar;

		if (ruleTabMap.containsKey(rule)) {
			occurrenceTab = ruleTabMap.get(rule);
			cancelOccurrenceSearch(occurrenceTab);
			occurrencesTabPane.getSelectionModel().select(occurrenceTab);
			layout = (VBox) occurrenceTab.getContent();
			header = (Label) layout.getChildren().get(0);
			progressBar = new ProgressBar(0);
			progressBar.setMaxWidth(Double.MAX_VALUE);
			layout.getChildren().setAll(header, progressBar);
			header.setText("Refreshing occurrences...");
			progressBar.setProgress(0);
		} else {
			occurrenceTab = new Tab();
			occurrenceTab.setText("Searching: " + rule.getPattern());
			ruleTabMap.put(rule, occurrenceTab);
			occurrenceTab.setOnClosed(_ -> {
				cancelOccurrenceSearch(occurrenceTab);
				ruleTabMap.remove(rule);
				if (ruleTabMap.isEmpty()) {
					openOccurrenceTabs.remove(state);
				}
			});

			layout = new VBox(10);
			layout.setStyle("-fx-padding: 10;");
			header = new Label("Searching for occurrences...");
			header.setStyle("-fx-font-weight: bold;");
			progressBar = new ProgressBar(0);
			progressBar.setMaxWidth(Double.MAX_VALUE);
			layout.getChildren().addAll(header, progressBar);

			occurrenceTab.setContent(layout);
			occurrencesTabPane.getTabs().add(occurrenceTab);
			occurrencesTabPane.getSelectionModel().select(occurrenceTab);
		}

		Button cancel = new Button("Cancel search");
		cancel.setOnAction(_ -> scanService.cancel(occurrenceTab));
		layout.getChildren().add(cancel);
		HighlightRule workerRule = new HighlightRule(rule.getPattern(), rule.getColor(), rule.isIsRegex());
		LogFileModel.Snapshot snapshot = state.logItems.snapshot();
		ScanService.Job<List<LogLine>> search = scanService.<List<LogLine>>submit(occurrenceTab, job -> {
			List<LogLine> occurrences = new ArrayList<>();
			int index = 0;
			while (index < snapshot.lineCount()) {
				if (job.isCancelled() || state.closed)
					return null;
				for (String line : snapshot.readBatch(index).getLines()) {
					if (job.isCancelled() || state.closed)
						return null;
					if (workerRule.matches(line))
						occurrences.add(new LogLine(index + 1, line));
					index++;
				}
			}
			return occurrences;
		}, (job, event) -> {
			if (!isCurrentOccurrenceSearch(state, occurrenceTab, job))
				return;
			switch (event.state()) {
			case QUEUED -> header.setText("Search queued...");
			case RUNNING -> header.setText("Searching for occurrences...");
			case CANCELLED, FAILED, REJECTED -> {
				occurrenceTab.getProperties().remove("searchJob");
				occurrenceTab.setText("Search " + event.state().name().toLowerCase(Locale.ROOT) + ": "
						+ workerRule.getPattern());
				String message = switch (event.state()) {
				case CANCELLED -> "Search cancelled; no results published.";
				case REJECTED -> "Scan capacity reached; click the highlight to try again.";
				default -> "Search incomplete; no results published. " + event.error().getMessage();
				};
				header.setText(message);
				header.setWrapText(true);
				header.setTooltip(new Tooltip(message));
				layout.getChildren().removeAll(progressBar, cancel);
			}
			case SUCCEEDED -> {
				occurrenceTab.getProperties().remove("searchJob");
				layout.getChildren().remove(cancel);
				if (state.logItems.snapshot().generation() != snapshot.generation()) {
					header.setText("File changed during search. Click the highlight to search again.");
					layout.getChildren().remove(progressBar);
					return;
				}
				List<LogLine> occurrences = event.result();
				progressBar.setProgress(1.0);
				if (occurrences.isEmpty()) {
					ruleTabMap.remove(rule);
					if (ruleTabMap.isEmpty())
						openOccurrenceTabs.remove(state);
					occurrencesTabPane.getTabs().remove(occurrenceTab);
					Alert alert = new Alert(Alert.AlertType.INFORMATION);
					alert.setTitle("No Occurrences");
					alert.setHeaderText(null);
					alert.setContentText("No occurrences found for highlight rule: " + rule.getPattern());
					alert.showAndWait();
					return;
				}

				occurrenceTab.setText(rule.getPattern() + " (" + state.file.getName() + ")");
				header.setText("Found " + occurrences.size() + " occurrences. Double-click to jump to line.");

				javafx.collections.ObservableList<LogLine> observableOccurrences = FXCollections.observableArrayList(
						occurrences);

				LogListView listView = new LogListView();
				listView.followingProperty().set(false);
				listView.setItems(observableOccurrences);
				configureRowHeight(listView);
				listView.setCellFactory(getHighlightingCellCallback(rule));
				setupCopyContextMenu(listView);
				listView.setOnMouseClicked(occurrenceNavigation(state, snapshot, listView));

				VBox.setVgrow(listView, Priority.ALWAYS);
				layout.getChildren().remove(progressBar);
				layout.getChildren().add(listView);
			}
			}
		});
		occurrenceTab.getProperties().put("searchJob", search);
	}

	private javafx.event.EventHandler<MouseEvent> occurrenceNavigation(TabState state,
			LogFileModel.Snapshot snapshot, LogListView listView) {
		return event -> {
			if (event.getClickCount() != 2 || state.closed
					|| state.logItems.snapshot().generation() != snapshot.generation())
				return;
			LogLine selected = listView.getSelectionModel().getSelectedItem();
			if (selected == null)
				return;
			for (Tab tab : tabPane.getTabs()) {
				if (tab.getUserData() == state) {
					tabPane.getSelectionModel().select(tab);
					break;
				}
			}
			int index = selected.lineNumber() - 1;
			if (index < 0 || index >= state.logItems.size())
				return;
			state.logListView.navigateTo(index);
		};
	}

	private void cancelOccurrenceSearch(Tab tab) {
		tab.getProperties().remove("searchJob");
		scanService.cancel(tab);
		if (tab.getContent() instanceof VBox box) {
			for (Node node : box.getChildren()) {
				if (node instanceof LogListView list)
					list.dispose();
			}
		}
	}

	private boolean isCurrentOccurrenceSearch(TabState state, Tab tab, ScanService.Job<?> job) {
		return !disposed && !state.closed && job.isCurrent() && tab.getProperties().get("searchJob") == job
				&& occurrencesTabPane.getTabs().contains(tab);
	}

	private Callback<ListView<LogLine>, ListCell<LogLine>> getHighlightingCellCallback() {
		return new Callback<>() {
			@Override
			public ListCell<LogLine> call(ListView<LogLine> param) {
				return new ListCell<>() {
					@Override
					protected void updateItem(LogLine item, boolean empty) {
						super.updateItem(item, empty);
						if (handleEmptyItem(this, item, empty))
							return;

						TextFlow textFlow = createTextFlow(item);
						int fontSize = fontSizeSpinner.getValue();
						String style = "-fx-font-family: 'monospace'; -fx-font-size: " + fontSize + "; -fx-padding: 0;";

						String content = item.content();
						if (highlightRules.isEmpty()) {
							addText(content, style, textFlow);
						} else {
							// Find all matches for all rules
							List<MatchWithColor> matches = findMatches(content);

							// Merge/Resolve overlapping matches (simplified: first one wins)
							int lastEnd = 0;
							for (MatchWithColor match : matches) {
								if (match.start < lastEnd)
									continue;

								// Add non-highlighted part
								if (match.start > lastEnd)
									addText(content.substring(lastEnd, match.start), style, textFlow);

								Label highlightLabel = new Label(content.substring(match.start, match.end));
								highlightLabel.setMinHeight(Region.USE_PREF_SIZE);
								highlightLabel.setStyle("-fx-font-family: 'monospace'; -fx-font-size: "
										+ fontSize
										+ "; -fx-background-color: "
										+ match.color
										+ "; -fx-padding: 0;");
								textFlow.getChildren().add(highlightLabel);

								lastEnd = match.end;
							}

							// Add remaining part
							if (lastEnd < content.length()) {
								addText(content.substring(lastEnd), style, textFlow);
							}
						}

						setGraphic(textFlow);
						setStyle(style);
					}
				};
			}
		};
	}

	private Callback<ListView<LogLine>, ListCell<LogLine>> getHighlightingCellCallback(HighlightRule rule) {
		return _ -> new ListCell<>() {
			@Override
			protected void updateItem(LogLine item, boolean empty) {
				super.updateItem(item, empty);
				if (handleEmptyItem(this, item, empty))
					return;

				TextFlow textFlow = createTextFlow(item);
				int fontSize = fontSizeSpinner.getValue();
				String style = "-fx-font-family: 'monospace'; -fx-font-size: " + fontSize + "; -fx-padding: 0;";

				String content = item.content();
				// Find all matches for this specific rule (which is 'rule' from showOccurrences)
				List<HighlightRule.MatchRange> ranges = rule.findMatches(content);
				int lastEnd = 0;
				for (HighlightRule.MatchRange range : ranges) {
					if (range.start() > lastEnd) {
						addText(content.substring(lastEnd, range.start()), style, textFlow);
					}
					Label highlightLabel = new Label(content.substring(range.start(), range.end()));
					highlightLabel.setMinHeight(Region.USE_PREF_SIZE);
					highlightLabel.setStyle("-fx-font-family: 'monospace'; -fx-font-size: "
							+ fontSize
							+ "; -fx-background-color: "
							+ rule.getColor()
							+ "; -fx-padding: 0;");
					textFlow.getChildren().add(highlightLabel);
					lastEnd = range.end();
				}
				if (lastEnd < content.length()) {
					addText(content.substring(lastEnd), style, textFlow);
				}

				setGraphic(textFlow);
				setStyle(style);
			}
		};
	}

	private List<MatchWithColor> findMatches(String content) {
		List<MatchWithColor> matches = new ArrayList<>();
		for (HighlightRule rule : highlightRules) {
			for (HighlightRule.MatchRange range : rule.findMatches(content)) {
				matches.add(new MatchWithColor(range.start(), range.end(), rule.getColor()));
			}
		}

		// Sort matches by start index
		matches.sort((m1, m2) -> {
			int cmp = Integer.compare(m1.start, m2.start);
			if (cmp != 0)
				return cmp;
			return Integer.compare(m2.end, m1.end); // longer first
		});

		return matches;
	}

	private static void addText(String content, String style, TextFlow textFlow) {
		Text contentText = new Text(content);
		contentText.setStyle(style);
		textFlow.getChildren().add(contentText);
	}

	private boolean handleEmptyItem(ListCell<LogLine> listCell, LogLine item, boolean empty) {
		listCell.setText(null);
		listCell.setTooltip(null);
		listCell.setDisable(!empty && item != null && !item.isAvailable());
		if (!empty && item != null) {
			if (item.isLoading()) {
				listCell.setGraphic(null);
				listCell.setText("Loading line " + item.lineNumber() + "…");
				listCell.setStyle("-fx-text-fill: gray; -fx-font-family: 'monospace';");
				return true;
			}
			if (item.isReadError()) {
				listCell.setGraphic(null);
				listCell.setText("Line " + item.lineNumber() + " unavailable: " + item.errorMessage());
				listCell.setTooltip(new Tooltip(listCell.getText()));
				listCell.setStyle("-fx-text-fill: firebrick; -fx-font-family: 'monospace';");
				return true;
			}
			return false;
		}

		listCell.setGraphic(null);
		listCell.setStyle("");
		return true;
	}

	private TextFlow createTextFlow(LogLine item) {
		TextFlow textFlow = new TextFlow();
		textFlow.setLineSpacing(0);
		textFlow.setPadding(Insets.EMPTY);
		textFlow.setMinWidth(Region.USE_PREF_SIZE);
		textFlow.setMouseTransparent(true);
		addText(String.format("%5d: ", item.lineNumber()),
				"-fx-font-family: 'monospace'; -fx-font-size: " + fontSizeSpinner.getValue() + "; -fx-fill: gray;", textFlow);
		return textFlow;
	}

	@FXML
	private void handleResetHighlights() {
		if (DialogUtil.showConfirmation("Reset Highlights", "Are you sure you want to reset all highlight groups?",
				"This will clear all current highlight groups and replace them with the default groups.")) {

			Config defaultConfig = configService.createDefaultConfig();

			// Replace all current groups in the combo box
			List<String> defaultGroupNames = defaultConfig
					.getHighlightGroups()
					.stream()
					.map(HighlightGroup::getName)
					.toList();

			ignoreGroupChange = true;
			try {
				highlightGroupComboBox.getItems().setAll(defaultGroupNames);

				// Reset to the default group
				currentGroup = defaultConfig.getLastGroup();
				highlightGroupComboBox.getSelectionModel().select(currentGroup);

				// Reset the active rules to match the new current group
				highlightRules.clear();
				defaultConfig
						.getHighlightGroups()
						.stream()
						.filter(g -> g.getName().equals(currentGroup))
						.findFirst()
						.ifPresent(g -> highlightRules.addAll(g.getRules()));

				updateHighlightsBar();

				// Save the default config directly
				Config finalDefaultConfig = defaultConfig;
				List<OpenFileInfo> openFiles = tabPane
						.getTabs()
						.stream()
						.map(t -> (TabState) t.getUserData())
						.map(s -> new OpenFileInfo(s.file.getAbsolutePath(), s.highlightGroup))
						.toList();
				defaultConfig = new Config(openFiles, defaultConfig.getHistory(), currentGroup,
						fontSizeSpinner.getValue(), finalDefaultConfig.getHighlightGroups());
				configService.saveConfig(defaultConfig);

				// Refresh the log view to apply the reset rules
				TabState activeTabState = getActiveTabState();
				if (activeTabState != null) {
					activeTabState.highlightCounts = null;
					activeTabState.lastCountedLine = 0;
					activeTabState.logListView.refresh();
				}
			} finally {
				ignoreGroupChange = false;
			}
		}
	}

	@FXML
	private void handleAbout() {
		try {
			FXMLLoader loader = new FXMLLoader(getClass().getResource("/ch/eitchnet/luminelog/view/about.fxml"));
			Parent root = loader.load();
			Stage stage = new Stage();
			stage
					.getIcons()
					.add(new Image(Objects.requireNonNull(
							getClass().getResourceAsStream("/ch/eitchnet/luminelog/assets/LumineLog.png"))));
			stage.setTitle("About LumineLog");
			stage.setScene(new Scene(root));
			stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
			stage.showAndWait();
		} catch (IOException e) {
			logger.error("Failed to show about dialog", e);
			DialogUtil.showError("Failed to show about dialog", e);
		}
	}

	private void updateFooterStatus(TabState state) {
		if (state == null || state.logModel == null) {
			this.statusLabel.setText("No file opened");
			return;
		}
		long size = state.logItems.snapshot().fileSize();
		long modified = state.logItems.snapshot().lastModified();
		String sizeStr = humanReadableSize(size);
		String dateStr = formatTimestamp(modified);
		this.statusLabel.setText("File: " + state.file.getAbsolutePath() + " | Size: " + sizeStr + " | Modified: " + dateStr);
	}

	private static String humanReadableSize(long bytes) {
		final String[] units = {"B", "KB", "MB", "GB", "TB"};
		double b = bytes;
		int unitIndex = 0;
		while (b >= 1024 && unitIndex < units.length - 1) {
			b /= 1024.0;
			unitIndex++;
		}
		return String.format(unitIndex == 0 ? "%.0f %s" : "%.1f %s", b, units[unitIndex]);
	}

	private static String formatTimestamp(long millis) {
		LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
		DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
		return dt.format(fmt);
	}

	@FXML
	private boolean handleExit() {
		if (!DialogUtil.showConfirmation("Exit LumineLog", "Exit LumineLog",
				"Are you sure you want to exit LumineLog?")) {
			return false;
		}

		saveHighlights();
		dispose();
		Platform.exit();
		return true;
	}

	public void dispose() {
		if (disposed)
			return;
		disposed = true;
		highlightsDelay.stop();
		cancelHighlightCount();
		if (tabPane != null) {
			for (Tab tab : tabPane.getTabs()) {
				if (tab.getUserData() instanceof TabState state)
					state.stopTailing();
			}
		}
		if (occurrencesTabPane != null) {
			for (Tab tab : occurrencesTabPane.getTabs())
				cancelOccurrenceSearch(tab);
		}
		openOccurrenceTabs.clear();
		scanService.close();
		lineLoadingService.close();
	}

	private String toWebColor(Color color) {
		return String.format("#%02X%02X%02X", (int) (color.getRed() * 255), (int) (color.getGreen() * 255),
				(int) (color.getBlue() * 255));
	}
}
