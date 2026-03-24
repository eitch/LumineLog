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
import ch.eitchnet.luminelog.util.DialogUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
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
import java.util.*;
import java.util.stream.Collectors;

public class MainController {

	private static final Logger logger = LoggerFactory.getLogger(MainController.class);

	private final ConfigService configService = new ConfigService();

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
		ListView<LogLine> logListView;
		int[] highlightCounts;
		int lastCountedLine = 0;

		TabState(File file, String highlightGroup, LogFileModel logModel, VirtualLogList logItems,
				ListView<LogLine> logListView) {
			this.file = file;
			this.highlightGroup = highlightGroup;
			this.logModel = logModel;
			this.logItems = logItems;
			this.logListView = logListView;
		}

		void stopTailing() {
			if (tailTimer != null) {
				tailTimer.cancel();
				tailTimer = null;
			}
			if (logModel != null) {
				try {
					logModel.close();
				} catch (IOException e) {
					// ignore
				}
			}
		}
	}

	@FXML
	public void initialize() {
		LumineLogApplication
				.getPrimaryStage()
				.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, _ -> this.handleExit());

		Config config = configService.loadConfig();
		fontSizeSpinner.setValueFactory(
				new SpinnerValueFactory.IntegerSpinnerValueFactory(8, 32, config.getFontSize()));
		fontSizeSpinner.valueProperty().addListener((_, _, _) -> {
			saveHighlights();
			TabState state = getActiveTabState();
			if (state != null) {
				state.logListView.refresh();
			}
		});

		highlightColorPicker.setValue(Color.valueOf("#ff8080"));
		loadHighlights(true);
		highlightGroupComboBox.getSelectionModel().select(currentGroup);

		occurrencesTabPane.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) _ -> {
			if (occurrencesTabPane.getTabs().isEmpty()) {
				mainSplitPane.setDividerPositions(1.0);
			} else if (mainSplitPane.getDividerPositions()[0] > 0.95) {
				mainSplitPane.setDividerPositions(0.7);
			}
		});
		mainSplitPane.setDividerPositions(1.0);

		tabPane.getSelectionModel().selectedItemProperty().addListener((_, oldTab, newTab) -> {
			if (oldTab != null && !ignoreGroupChange) {
				saveHighlights();
			}

			if (newTab != null) {
				TabState state = (TabState) newTab.getUserData();
				statusLabel.setText("File: " + state.file.getAbsolutePath());

				if (state.highlightGroup != null && !state.highlightGroup.equals(currentGroup)) {
					currentGroup = state.highlightGroup;
					loadHighlights(false);
					highlightGroupComboBox.getSelectionModel().select(currentGroup);
				}
			} else {
				statusLabel.setText("No file opened");
			}
			updateHighlightsBar();
		});

		tailCheckBox.selectedProperty().addListener((_, _, isSelected) -> {
			if (isSelected) {
				TabState state = getActiveTabState();
				if (state != null) {
					int lineCount = state.logItems.size();
					if (lineCount > 0) {
						state.logListView.scrollTo(lineCount - 1);
					}
				}
			}
		});

		tabPane.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> {
			while (change.next()) {
				if (change.wasRemoved()) {
					for (Tab tab : change.getRemoved()) {
						TabState state = (TabState) tab.getUserData();
						if (state != null) {
							java.util.Map<HighlightRule, Tab> ruleTabMap = openOccurrenceTabs.remove(state);
							if (ruleTabMap != null) {
								occurrencesTabPane.getTabs().removeAll(ruleTabMap.values());
							}
						}
					}
				}
			}
		});

		Platform.runLater(this::loadLastFiles);
		setupDragAndDrop();
	}

	private void setupDragAndDrop() {
		rootContainer.setOnDragOver(event -> {
			if (event.getGestureSource() != rootContainer && event.getDragboard().hasFiles()) {
				event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
			}
			event.consume();
		});

		rootContainer.setOnDragDropped(event -> {
			Dragboard db = event.getDragboard();
			boolean success = false;
			if (db.hasFiles()) {
				for (File file : db.getFiles()) {
					openFile(file, currentGroup);
				}
				success = true;
			}
			event.setDropCompleted(success);
			event.consume();
		});
	}

	private void loadLastFiles() {
		Config config = configService.loadConfig();
		List<OpenFileInfo> openFiles = config.getOpenFiles();
		if (!openFiles.isEmpty()) {
			for (OpenFileInfo openFileInfo : openFiles) {
				File file = new File(openFileInfo.filePath());
				if (file.exists()) {
					openFile(file, openFileInfo.highlightGroup());
				}
			}
		}
	}

	private void saveHighlights() {
		Config currentConfig = configService.loadConfig();

		List<HighlightGroup> groups = new ArrayList<>();
		for (String groupName : highlightGroupComboBox.getItems()) {
			List<HighlightRule> rules;
			if (groupName.equals(currentGroup)) {
				rules = new ArrayList<>(highlightRules);
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

		List<OpenFileInfo> openFiles = tabPane
				.getTabs()
				.stream()
				.map(t -> (TabState) t.getUserData())
				.map(s -> new OpenFileInfo(s.file.getAbsolutePath(), s.highlightGroup))
				.toList();

		Config newConfig = new Config(openFiles, currentGroup, fontSizeSpinner.getValue(), groups);

		configService.saveConfig(newConfig);
	}

	private boolean ignoreGroupChange = false;

	private void loadHighlights(boolean updateGroups) {
		ignoreGroupChange = true;
		try {
			Config config = configService.loadConfig();
			logger.info("Loading highlights from JSON config");

			if (currentGroup == null || currentGroup.isEmpty()) {
				currentGroup = config.getLastGroup();
				if (currentGroup == null)
					currentGroup = "Default";
			}

			if (updateGroups) {
				List<String> groupNames = config
						.getHighlightGroups()
						.stream()
						.map(HighlightGroup::getName)
						.collect(Collectors.toList());

				if (groupNames.isEmpty()) {
					groupNames.add("Default");
					currentGroup = "Default";
				}

				highlightGroupComboBox.getItems().setAll(groupNames);
				highlightGroupComboBox.getSelectionModel().select(currentGroup);
			}

			highlightRules.clear();
			config
					.getHighlightGroups()
					.stream()
					.filter(g -> g.getName().equals(currentGroup))
					.findFirst()
					.ifPresent(g -> highlightRules.addAll(g.getRules()));

			updateHighlightsBar();
		} finally {
			ignoreGroupChange = false;
		}
	}

	@FXML
	private void handleGroupChange() {
		if (ignoreGroupChange) {
			return;
		}

		String newGroup = highlightGroupComboBox.getValue();
		if (newGroup == null || newGroup.isEmpty() || newGroup.equals(currentGroup)) {
			return;
		}

		saveHighlights();
		currentGroup = newGroup;
		if (!highlightGroupComboBox.getItems().contains(currentGroup)) {
			highlightGroupComboBox.getItems().add(currentGroup);
		}

		TabState state = getActiveTabState();
		if (state != null) {
			state.highlightGroup = currentGroup;
		}

		loadHighlights(false);

		if (state != null) {
			state.logListView.refresh();
		}
	}

	@FXML
	private void handleAddGroup() {
		TextInputDialog dialog = new TextInputDialog("New Group");
		dialog.setTitle("New Highlight Group");
		dialog.setHeaderText("Create a new highlight group");
		dialog.setContentText("Please enter the name of the new group:");

		dialog.showAndWait().ifPresent(name -> {
			String newGroup = name.trim();
			if (newGroup.isEmpty()) {
				DialogUtil.showError("Group name cannot be empty");
				return;
			}
			if (highlightGroupComboBox.getItems().contains(newGroup)) {
				DialogUtil.showError("Group '" + newGroup + "' already exists");
				return;
			}

			saveHighlights();
			ignoreGroupChange = true;
			try {
				currentGroup = newGroup;
				highlightGroupComboBox.getItems().add(newGroup);
				highlightGroupComboBox.getSelectionModel().select(newGroup);
				highlightRules.clear();
				updateHighlightsBar();
				saveHighlights();
			} finally {
				ignoreGroupChange = false;
			}
		});
	}

	@FXML
	private void handleRenameGroup() {
		String oldGroup = highlightGroupComboBox.getValue();
		if (oldGroup == null || oldGroup.equals("Default")) {
			DialogUtil.showError("The 'Default' group cannot be renamed.");
			return;
		}

		TextInputDialog dialog = new TextInputDialog(oldGroup);
		dialog.setTitle("Rename Highlight Group");
		dialog.setHeaderText("Rename highlight group '" + oldGroup + "'");
		dialog.setContentText("Please enter the new name for the group:");

		dialog.showAndWait().ifPresent(name -> {
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
		});
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
		if (alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
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
	}

	private void closeTab(Tab tab) {
		TabState state = (TabState) tab.getUserData();
		if (state != null) {
			if (DialogUtil.showConfirmation("Close File", "Are you sure you want to close this file?",
					state.file.getAbsolutePath())) {
				state.stopTailing();
				tabPane.getTabs().remove(tab);
				saveHighlights();
			}
		}
	}

	@FXML
	private void handleClose() {
		Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
		if (selectedTab != null) {
			closeTab(selectedTab);
		}
	}

	@FXML
	private void handleOpen() {
		FileChooser fileChooser = new FileChooser();
		fileChooser.setTitle("Open Log File");
		File file = fileChooser.showOpenDialog(tabPane.getScene().getWindow());
		if (file != null) {
			openFile(file, currentGroup);
		}
	}

	private void openFile(File file, String highlightGroup) {
		try {
			LogFileModel logModel = new LogFileModel(file.toPath());
			VirtualLogList logItems = new VirtualLogList(logModel);
			ListView<LogLine> logListView = createLogListView();

			Tab tab = new Tab();
			Label tabLabel = new Label(file.getName());
			Button closeBtn = new Button("×");
			closeBtn.setStyle("-fx-background-color: transparent; -fx-padding: 0 0 0 5; -fx-cursor: hand;");
			TabState state = new TabState(file, highlightGroup, logModel, logItems, logListView);
			closeBtn.setOnAction(_ -> closeTab(tab));
			HBox graphic = new HBox(tabLabel, closeBtn);
			graphic.setAlignment(Pos.CENTER);
			tab.setGraphic(graphic);
			tab.setClosable(false);
			tab.setContent(logListView);

			graphic.setOnMouseReleased(event -> {
				if (event.getButton() == MouseButton.MIDDLE) {
					closeTab(tab);
				}
			});
			tab.setUserData(state);
			tab.setOnClosed(_ -> {
				logger.info("Closing tab for file {}", file.getAbsolutePath());
				state.stopTailing();
			});

			tabPane.getTabs().add(tab);
			tabPane.getSelectionModel().select(tab);

			updateHighlightsBar();
			refreshLogView(state);
			startTailing(state);

			Platform.runLater(() -> {
				logListView.setItems(logItems);
				if (tailCheckBox.isSelected()) {
					int lineCount = logItems.size();
					if (lineCount > 0) {
						logListView.scrollTo(lineCount - 1);
					}
				}
				setupScrollBarListener(tab, logListView);
			});
		} catch (IOException e) {
			DialogUtil.showError("Could not open file: " + e.getMessage());
		}
	}

	private void setupScrollBarListener(Tab tab, ListView<LogLine> logListView) {
		Node scrollBarNode = logListView.lookup(".scroll-bar:vertical");
		if (scrollBarNode instanceof ScrollBar scrollBar) {
			scrollBar.valueProperty().addListener((_, oldVal, newVal) -> {
				if (tabPane.getSelectionModel().getSelectedItem() != tab)
					return;

				double value = newVal.doubleValue();
				if (value < oldVal.doubleValue()) {
					if (tailCheckBox.isSelected()) {
						tailCheckBox.setSelected(false);
					}
				} else {
					double max = scrollBar.getMax();
					if (value >= max - 0.5) {
						if (!tailCheckBox.isSelected()) {
							tailCheckBox.setSelected(true);
						}
					}
				}
			});
		} else {
			if (tabPane.getTabs().contains(tab)) {
				Platform.runLater(() -> setupScrollBarListener(tab, logListView));
			}
		}
	}

	private void copyToClipboard(String text) {
		if (text != null && !text.isEmpty()) {
			Clipboard clipboard = Clipboard.getSystemClipboard();
			ClipboardContent content = new ClipboardContent();
			content.putString(text);
			clipboard.setContent(content);
		}
	}

	private void setupCopyContextMenu(ListView<LogLine> listView) {
		ContextMenu contextMenu = new ContextMenu();
		MenuItem copyItem = new MenuItem("Copy Line");
		copyItem.setOnAction(_ -> {
			LogLine selected = listView.getSelectionModel().getSelectedItem();
			if (selected != null) {
				copyToClipboard(selected.content());
			}
		});
		contextMenu.getItems().add(copyItem);

		listView.setContextMenu(contextMenu);

		// Optional: Keyboard shortcut for copy
		listView.setOnKeyPressed(event -> {
			if (event.isControlDown() && event.getCode() == KeyCode.C) {
				LogLine selected = listView.getSelectionModel().getSelectedItem();
				if (selected != null) {
					copyToClipboard(selected.content());
				}
			}
		});
	}

	private ListView<LogLine> createLogListView() {
		ListView<LogLine> logListView = new ListView<>();
		logListView.setCellFactory(getHighlightingCellCallback());
		setupCopyContextMenu(logListView);
		return logListView;
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

	private void refreshLogView(TabState state) {
		if (state != null && state.logItems != null) {
			int lineCount = state.logModel.getLineCount();
			state.logItems.fireSizeChanged(0, lineCount);
			if (lineCount > 0) {
				state.logListView.scrollTo(lineCount - 1);
			}
			state.logListView.refresh();
		}
	}

	private void startTailing(TabState state) {
		state.tailTimer = new Timer(true);
		state.tailTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (state.logModel != null) {
					try {
						int oldCount = state.logModel.getLineCount();
						state.logModel.updateIndex();
						int newCount = state.logModel.getLineCount();
						if (newCount != oldCount) {
							Platform.runLater(() -> {
								state.logItems.fireSizeChanged(oldCount, newCount);
								updateHighlightsBar();

								if (tailCheckBox.isSelected() && newCount > oldCount) {
									state.logListView.scrollTo(newCount - 1);
								}
							});
						}
					} catch (IOException e) {
						// ignore or log
					}
				}
			}
		}, 50, 50);
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
	private Thread highlightCounterThread = null;

	private void updateHighlightsBar() {
		TabState state = getActiveTabState();
		if (state == null || state.logModel == null || highlightRules.isEmpty()) {
			highlightsPane.getChildren().clear();
			if (state != null) {
				state.highlightCounts = null;
				state.lastCountedLine = 0;
			}
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastUpdateHighlightsBar < 500) {
			return;
		}
		lastUpdateHighlightsBar = now;

		if (highlightCounterThread != null && highlightCounterThread.isAlive()) {
			highlightCounterThread.interrupt();
		}

		List<HighlightRule> currentRules = new ArrayList<>(highlightRules);
		highlightCounterThread = new Thread(() -> {
			int numRules = currentRules.size();
			if (state.highlightCounts == null || state.highlightCounts.length != numRules) {
				state.highlightCounts = new int[numRules];
				state.lastCountedLine = 0;
			}

			int startLine = state.lastCountedLine;
			int totalLines = state.logModel.getLineCount();

			if (startLine < totalLines) {
				try {
					final int batchSize = 1000;
					final long updateIntervalMs = 200;
					final long[] lastUpdate = {System.currentTimeMillis()};

					state.logModel.iterateLines(startLine, (line, lineNumber) -> {
						if (Thread.currentThread().isInterrupted()) {
							return false;
						}

						for (int j = 0; j < numRules; j++) {
							state.highlightCounts[j] += currentRules.get(j).countOccurrences(line);
						}
						state.lastCountedLine = lineNumber + 1;

						long currentTime = System.currentTimeMillis();
						if (lineNumber % batchSize == 0 && (currentTime - lastUpdate[0] > updateIntervalMs)) {
							lastUpdate[0] = currentTime;
							final int[] intermediateCounts = state.highlightCounts.clone();
							Platform.runLater(() -> updateHighlightsUI(currentRules, intermediateCounts));
						}
						return true;
					});
				} catch (IOException e) {
					// ignore
				}
			}

			final int[] finalCounts = state.highlightCounts.clone();
			Platform.runLater(() -> updateHighlightsUI(currentRules, finalCounts));
		}, "HighlightCounter");
		highlightCounterThread.setDaemon(true);
		highlightCounterThread.start();
	}

	private void updateHighlightsUI(List<HighlightRule> currentRules, int[] counts) {
		highlightsPane.getChildren().clear();
		for (int i = 0; i < currentRules.size(); i++) {
			HighlightRule rule = currentRules.get(i);
			int count = counts[i];

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
		if (state == null || state.logModel == null)
			return;

		java.util.Map<HighlightRule, Tab> ruleTabMap = openOccurrenceTabs.computeIfAbsent(state,
				_ -> new java.util.HashMap<>());
		Tab occurrenceTab;
		VBox layout;
		Label header;
		ProgressBar progressBar;

		if (ruleTabMap.containsKey(rule)) {
			occurrenceTab = ruleTabMap.get(rule);
			occurrencesTabPane.getSelectionModel().select(occurrenceTab);
			layout = (VBox) occurrenceTab.getContent();
			header = (Label) layout.getChildren().get(0);
			if (layout.getChildren().size() > 1 && layout.getChildren().get(1) instanceof ListView) {
				layout.getChildren().remove(1); // remove the ListView to show progress
			}
			if (layout.getChildren().size() == 1) {
				progressBar = new ProgressBar(0);
				progressBar.setMaxWidth(Double.MAX_VALUE);
				layout.getChildren().add(progressBar);
			} else {
				progressBar = (ProgressBar) layout.getChildren().get(1);
			}
			header.setText("Refreshing occurrences...");
			progressBar.setProgress(0);
		} else {
			occurrenceTab = new Tab();
			occurrenceTab.setText("Searching: " + rule.getPattern());
			ruleTabMap.put(rule, occurrenceTab);
			occurrenceTab.setOnClosed(_ -> {
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

		new Thread(() -> {
			List<LogLine> occurrences = new ArrayList<>();
			try {
				int totalLines = state.logModel.getLineCount();
				state.logModel.iterateLines((line, lineNumber) -> {
					if (line != null && rule.matches(line)) {
						occurrences.add(new LogLine(lineNumber + 1, line));
					}
					if (lineNumber % 1000 == 0) {
						double progress = (double) lineNumber / totalLines;
						Platform.runLater(() -> {
							progressBar.setProgress(progress);
							header.setText("Searching for occurrences... (" + occurrences.size() + " found)");
						});
					}
					return true;
				});
			} catch (IOException e) {
				Platform.runLater(() -> {
					ruleTabMap.remove(rule);
					if (ruleTabMap.isEmpty())
						openOccurrenceTabs.remove(state);
					occurrencesTabPane.getTabs().remove(occurrenceTab);
					DialogUtil.showError("Error searching for occurrences: " + e.getMessage());
				});
				return;
			}

			Platform.runLater(() -> {
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

				ListView<LogLine> listView = new ListView<>(observableOccurrences);
				listView.setCellFactory(getHighlightingCellCallback(rule));
				setupCopyContextMenu(listView);
				listView.setOnMouseClicked(event -> {
					if (event.getClickCount() == 2) {
						LogLine selected = listView.getSelectionModel().getSelectedItem();
						if (selected != null) {
							int index = selected.lineNumber() - 1;
							state.logListView.getSelectionModel().select(index);
							state.logListView.scrollTo(index);
						}
					}
				});

				VBox.setVgrow(listView, Priority.ALWAYS);
				layout.getChildren().remove(progressBar);
				layout.getChildren().add(listView);
			});
		}).start();
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
		if (!empty && item != null)
			return false;

		listCell.setText(null);
		listCell.setGraphic(null);
		listCell.setStyle("");
		return true;
	}

	private static TextFlow createTextFlow(LogLine item) {
		TextFlow textFlow = new TextFlow();
		textFlow.setLineSpacing(0);
		textFlow.setPadding(Insets.EMPTY);
		textFlow.setMinHeight(16);
		textFlow.setMaxHeight(16);
		addText(String.format("%5d: ", item.lineNumber()),
				"-fx-font-family: 'monospace'; -fx-font-size: 12; -fx-fill: gray;", textFlow);
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
				defaultConfig = new Config(openFiles, currentGroup, fontSizeSpinner.getValue(),
						finalDefaultConfig.getHighlightGroups());
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

	@FXML
	private void handleExit() {
		if (DialogUtil.showConfirmation("Exit LumineLog", "Exit LumineLog",
				"Are you sure you want to exit LumineLog?")) {
			saveHighlights();
			for (Tab tab : tabPane.getTabs()) {
				TabState state = (TabState) tab.getUserData();
				if (state != null) {
					state.stopTailing();
				}
			}
			Platform.exit();
		}
	}

	private String toWebColor(Color color) {
		return String.format("#%02X%02X%02X", (int) (color.getRed() * 255), (int) (color.getGreen() * 255),
				(int) (color.getBlue() * 255));
	}
}
