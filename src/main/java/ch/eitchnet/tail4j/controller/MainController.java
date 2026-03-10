package ch.eitchnet.tail4j.controller;

import ch.eitchnet.tail4j.model.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

public class MainController {

	private static final Logger logger = LoggerFactory.getLogger(MainController.class);

	private final ConfigService configService = new ConfigService();

	@FXML
	private TabPane tabPane;
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
	private ComboBox<String> highlightGroupComboBox;
	@FXML
	private javafx.scene.layout.FlowPane highlightsPane;

	private String currentGroup = null;
	private final List<HighlightRule> highlightRules = new ArrayList<>();
	private final java.util.Map<HighlightRule, Stage> openOccurrenceStages = new java.util.HashMap<>();

	private static class TabState {
		File file;
		LogFileModel logModel;
		VirtualLogList logItems;
		Timer tailTimer;
		ListView<LogLine> logListView;
		int[] highlightCounts;
		int lastCountedLine = 0;

		TabState(File file, LogFileModel logModel, VirtualLogList logItems, ListView<LogLine> logListView) {
			this.file = file;
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
		highlightColorPicker.setValue(Color.valueOf("#ff8080"));
		loadHighlights();
		highlightGroupComboBox.getSelectionModel().select(currentGroup);

		tabPane.getSelectionModel().selectedItemProperty().addListener((_, _, newTab) -> {
			if (newTab != null) {
				TabState state = (TabState) newTab.getUserData();
				statusLabel.setText("File: " + state.file.getAbsolutePath());
			} else {
				statusLabel.setText("No file opened");
			}
			updateHighlightsBar();
		});

		Platform.runLater(this::loadLastFile);
	}

	private void loadLastFile() {
		Config config = configService.loadConfig();
		String lastFile = config.getLastOpenFile();
		if (lastFile != null) {
			File file = new File(lastFile);
			if (file.exists()) {
				openFile(file);
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

		Config newConfig = new Config(getActiveTabState() != null ? getActiveTabState().file.getAbsolutePath() :
				currentConfig.getLastOpenFile(), currentGroup, groups);

		configService.saveConfig(newConfig);
	}

	private boolean ignoreGroupChange = false;

	private void loadHighlights() {
		ignoreGroupChange = true;
		try {
			Config config = configService.loadConfig();
			logger.info("Loading highlights from JSON config");

			if (currentGroup == null || currentGroup.isEmpty()) {
				currentGroup = config.getLastGroup();
				if (currentGroup == null)
					currentGroup = "Default";
			}

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
		loadHighlights();

		TabState state = getActiveTabState();
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
				showError("Group name cannot be empty");
				return;
			}
			if (highlightGroupComboBox.getItems().contains(newGroup)) {
				showError("Group '" + newGroup + "' already exists");
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
			showError("The 'Default' group cannot be renamed.");
			return;
		}

		TextInputDialog dialog = new TextInputDialog(oldGroup);
		dialog.setTitle("Rename Highlight Group");
		dialog.setHeaderText("Rename highlight group '" + oldGroup + "'");
		dialog.setContentText("Please enter the new name for the group:");

		dialog.showAndWait().ifPresent(name -> {
			String newGroup = name.trim();
			if (newGroup.isEmpty()) {
				showError("Group name cannot be empty");
				return;
			}
			if (newGroup.equals(oldGroup)) {
				return;
			}
			if (highlightGroupComboBox.getItems().contains(newGroup)) {
				showError("Group '" + newGroup + "' already exists");
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

	@FXML
	private void handleOpen() {
		FileChooser fileChooser = new FileChooser();
		fileChooser.setTitle("Open Log File");
		File file = fileChooser.showOpenDialog(tabPane.getScene().getWindow());
		if (file != null) {
			openFile(file);
		}
	}

	private void openFile(File file) {
		try {
			LogFileModel logModel = new LogFileModel(file.toPath());
			VirtualLogList logItems = new VirtualLogList(logModel);
			ListView<LogLine> logListView = createLogListView();

			Tab tab = new Tab();
			Label tabLabel = new Label(file.getName());
			Button closeBtn = new Button("×");
			closeBtn.setStyle("-fx-background-color: transparent; -fx-padding: 0 0 0 5; -fx-cursor: hand;");
			TabState state = new TabState(file, logModel, logItems, logListView);
			closeBtn.setOnAction(_ -> {
				state.stopTailing();
				tabPane.getTabs().remove(tab);
			});
			HBox graphic = new HBox(tabLabel, closeBtn);
			graphic.setAlignment(Pos.CENTER);
			tab.setGraphic(graphic);
			tab.setClosable(false);
			tab.setContent(logListView);

			graphic.setOnMouseReleased(event -> {
				if (event.getButton() == MouseButton.MIDDLE) {
					state.stopTailing();
					tabPane.getTabs().remove(tab);
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

			Platform.runLater(() -> logListView.setItems(logItems));

		} catch (IOException e) {
			showError("Could not open file: " + e.getMessage());
		}
	}

	private ListView<LogLine> createLogListView() {
		ListView<LogLine> logListView = new ListView<>();
		logListView.setCellFactory(getHighlightingCellCallback());
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
		state.tailTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				if (tailCheckBox.isSelected() && state.logModel != null) {
					try {
						int oldCount = state.logModel.getLineCount();
						state.logModel.updateIndex();
						int newCount = state.logModel.getLineCount();
						if (newCount != oldCount) {
							Platform.runLater(() -> {
								state.logItems.fireSizeChanged(oldCount, newCount);
								updateHighlightsBar();
								if (newCount > oldCount) {
									state.logListView.scrollTo(newCount - 1);
								}
							});
						}
					} catch (IOException e) {
						// ignore or log
					}
				}
			}
		}, 1000, 1000);
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

	@FXML
	private void handleClearHighlights() {
		highlightRules.clear();
		saveHighlights();

		TabState state = getActiveTabState();
		if (state != null) {
			state.highlightCounts = null;
			state.lastCountedLine = 0;
			state.logListView.refresh();
		}
		updateHighlightsBar();
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
		if (openOccurrenceStages.containsKey(rule)) {
			openOccurrenceStages.get(rule).toFront();
			return;
		}

		TabState state = getActiveTabState();
		if (state == null || state.logModel == null)
			return;

		Stage stage = new Stage();
		stage.setTitle("Searching occurrences for: " + rule.getPattern());
		openOccurrenceStages.put(rule, stage);
		stage.setOnCloseRequest(_ -> openOccurrenceStages.remove(rule));

		VBox layout = new VBox(10);
		layout.setStyle("-fx-padding: 10;");
		Label header = new Label("Searching for occurrences...");
		header.setStyle("-fx-font-weight: bold;");
		ProgressBar progressBar = new ProgressBar(0);
		progressBar.setMaxWidth(Double.MAX_VALUE);
		layout.getChildren().addAll(header, progressBar);

		Scene scene = new Scene(layout, 1000, 800);
		stage.setScene(scene);
		stage.show();

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
					openOccurrenceStages.remove(rule);
					stage.close();
					showError("Error searching for occurrences: " + e.getMessage());
				});
				return;
			}

			Platform.runLater(() -> {
				progressBar.setProgress(1.0);
				if (occurrences.isEmpty()) {
					openOccurrenceStages.remove(rule);
					stage.close();
					Alert alert = new Alert(Alert.AlertType.INFORMATION);
					alert.setTitle("No Occurrences");
					alert.setHeaderText(null);
					alert.setContentText("No occurrences found for highlight rule: " + rule.getPattern());
					alert.showAndWait();
					return;
				}

				stage.setTitle("Occurrences for: " + rule.getPattern());
				header.setText("Found " + occurrences.size() + " occurrences. Double-click to jump to line.");

				ListView<LogLine> listView = new ListView<>(FXCollections.observableArrayList(occurrences));
				listView.setCellFactory(getHighlightingCellCallback(rule));

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

				VBox.setVgrow(listView, javafx.scene.layout.Priority.ALWAYS);
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

						String content = item.content();
						if (highlightRules.isEmpty()) {
							addText(content, "-fx-font-family: 'monospace'; -fx-font-size: 12; -fx-padding: 0;", textFlow);
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
									addText(content.substring(lastEnd, match.start),
											"-fx-font-family: 'monospace'; -fx-font-size: 12; -fx-padding: 0;", textFlow);

								Label highlightLabel = new Label(content.substring(match.start, match.end));
								highlightLabel.setMinHeight(Region.USE_PREF_SIZE);
								highlightLabel.setStyle(
										"-fx-font-family: 'monospace'; -fx-font-size: 12; -fx-background-color: "
												+ match.color
												+ "; -fx-padding: 0;");
								textFlow.getChildren().add(highlightLabel);

								lastEnd = match.end;
							}

							// Add remaining part
							if (lastEnd < content.length()) {
								addText(content.substring(lastEnd), "-fx-font-family: 'monospace'; -fx-font-size: 12; -fx-padding: 0;",
										textFlow);
							}
						}

						setGraphic(textFlow);
						setStyle("-fx-font-family: 'monospace'; -fx-font-size: 12; -fx-padding: 0;");
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

				String content = item.content();
				// Find all matches for this specific rule (which is 'rule' from showOccurrences)
				List<HighlightRule.MatchRange> ranges = rule.findMatches(content);
				int lastEnd = 0;
				for (HighlightRule.MatchRange range : ranges) {
					if (range.start() > lastEnd) {
						addText(content.substring(lastEnd, range.start()),
								"-fx-font-family: 'monospace'; -fx-font-size: 12; -fx-padding: 0;", textFlow);
					}
					Label highlightLabel = new Label(content.substring(range.start(), range.end()));
					highlightLabel.setMinHeight(Region.USE_PREF_SIZE);
					highlightLabel.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 12; -fx-background-color: "
							+ rule.getColor()
							+ "; -fx-padding: 0;");
					textFlow.getChildren().add(highlightLabel);
					lastEnd = range.end();
				}
				if (lastEnd < content.length()) {
					addText(content.substring(lastEnd), "-fx-font-family: 'monospace'; -fx-font-size: 12; -fx-padding: 0;", textFlow);
				}

				setGraphic(textFlow);
				setStyle("-fx-font-family: 'monospace'; -fx-font-size: 12; -fx-padding: 0;");
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
	private void handleExit() {
		saveHighlights();
		for (Tab tab : tabPane.getTabs()) {
			TabState state = (TabState) tab.getUserData();
			if (state != null) {
				state.stopTailing();
			}
		}
		Platform.exit();
	}

	private String toWebColor(Color color) {
		return String.format("#%02X%02X%02X", (int) (color.getRed() * 255), (int) (color.getGreen() * 255),
				(int) (color.getBlue() * 255));
	}

	private void showError(String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setContentText(message);
		alert.showAndWait();
	}
}
