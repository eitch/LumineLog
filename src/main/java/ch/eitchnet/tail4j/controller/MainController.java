package ch.eitchnet.tail4j.controller;

import ch.eitchnet.tail4j.model.HighlightRule;
import ch.eitchnet.tail4j.model.LogFileModel;
import ch.eitchnet.tail4j.model.LogLine;
import ch.eitchnet.tail4j.model.VirtualLogList;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Callback;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainController {

	private static final Logger logger = LoggerFactory.getLogger(MainController.class);

	private static final String PREF_LAST_OPEN_FILE = "lastOpenFile";
	private static final String PREF_LAST_GROUP = "lastGroup";
	private static final String PREF_GROUPS = "highlightGroups";
	private static final String PREF_HIGHLIGHT_COUNT = "highlightCount_";
	private static final String PREF_HIGHLIGHT_PATTERN = "highlightPattern_";
	private static final String PREF_HIGHLIGHT_COLOR = "highlightColor_";
	private static final String PREF_HIGHLIGHT_IS_REGEX = "highlightIsRegex_";

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

	private String currentGroup = "Default";
	private final List<HighlightRule> highlightRules = new ArrayList<>();

	private static class TabState {
		File file;
		LogFileModel logModel;
		VirtualLogList logItems;
		Timer tailTimer;
		ListView<LogLine> logListView;

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
		}
	}

	@FXML
	public void initialize() {
		highlightColorPicker.setValue(Color.RED);
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
		Preferences prefs = Preferences.userNodeForPackage(MainController.class);
		String lastFile = prefs.get(PREF_LAST_OPEN_FILE, null);
		if (lastFile != null) {
			File file = new File(lastFile);
			if (file.exists()) {
				openFile(file);
			}
		}
	}

	private void saveHighlights() {
		Preferences prefs = Preferences.userNodeForPackage(MainController.class);
		logger.info("Saving highlights for group {} to preferences node: {}", currentGroup, prefs.absolutePath());

		String groups = String.join(",", highlightGroupComboBox.getItems());
		prefs.put(PREF_GROUPS, groups);
		prefs.put(PREF_LAST_GROUP, currentGroup);

		prefs.putInt(PREF_HIGHLIGHT_COUNT + currentGroup, highlightRules.size());
		for (int i = 0; i < highlightRules.size(); i++) {
			HighlightRule rule = highlightRules.get(i);
			prefs.put(PREF_HIGHLIGHT_PATTERN + currentGroup + "_" + i, rule.pattern());
			prefs.put(PREF_HIGHLIGHT_COLOR + currentGroup + "_" + i, rule.color());
			prefs.putBoolean(PREF_HIGHLIGHT_IS_REGEX + currentGroup + "_" + i, rule.isRegex());
		}

		try {
			prefs.flush();
		} catch (Exception e) {
			logger.error("Failed to flush preferences", e);
		}
	}

	private boolean ignoreGroupChange = false;

	private void loadHighlights() {
		ignoreGroupChange = true;
		try {
			Preferences prefs = Preferences.userNodeForPackage(MainController.class);
			logger.info("Loading highlights from preferences node: {}", prefs.absolutePath());

			String groupsStr = prefs.get(PREF_GROUPS, "Default");
			String[] groups = groupsStr.split(",");
			highlightGroupComboBox.getItems().setAll(groups);

			currentGroup = prefs.get(PREF_LAST_GROUP, "Default");
			if (highlightGroupComboBox.getItems().isEmpty()) {
				highlightGroupComboBox.getItems().add("Default");
				currentGroup = "Default";
			}
			highlightGroupComboBox.getSelectionModel().select(currentGroup);

			int count = prefs.getInt(PREF_HIGHLIGHT_COUNT + currentGroup, 0);

			// Migration: if no highlights in currentGroup, check old keys
			if (count == 0 && currentGroup.equals("Default")) {
				count = prefs.getInt("highlightCount", 0);
				if (count > 0) {
					logger.info("Migrating highlights from old format...");
					highlightRules.clear();
					for (int i = 0; i < count; i++) {
						String pattern = prefs.get("highlightPattern_" + i, null);
						String color = prefs.get("highlightColor_" + i, null);
						boolean isRegex = prefs.getBoolean("highlightIsRegex_" + i, false);
						if (pattern != null && color != null) {
							highlightRules.add(new HighlightRule(pattern, color, isRegex));
						}
					}
					saveHighlights();
					updateHighlightsBar();
					return;
				}
			}

			highlightRules.clear();
			for (int i = 0; i < count; i++) {
				String pattern = prefs.get(PREF_HIGHLIGHT_PATTERN + currentGroup + "_" + i, null);
				String color = prefs.get(PREF_HIGHLIGHT_COLOR + currentGroup + "_" + i, null);
				boolean isRegex = prefs.getBoolean(PREF_HIGHLIGHT_IS_REGEX + currentGroup + "_" + i, false);
				if (pattern != null && color != null) {
					highlightRules.add(new HighlightRule(pattern, color, isRegex));
				}
			}
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
			refreshLogView(state);
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
				highlightGroupComboBox.getItems().add(newGroup);
				highlightGroupComboBox.getSelectionModel().select(newGroup);
				currentGroup = newGroup;
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

			saveHighlights();

			// Move highlights in preferences
			Preferences prefs = Preferences.userNodeForPackage(MainController.class);
			int count = prefs.getInt(PREF_HIGHLIGHT_COUNT + oldGroup, 0);
			prefs.putInt(PREF_HIGHLIGHT_COUNT + newGroup, count);
			for (int i = 0; i < count; i++) {
				String pattern = prefs.get(PREF_HIGHLIGHT_PATTERN + oldGroup + "_" + i, "");
				String color = prefs.get(PREF_HIGHLIGHT_COLOR + oldGroup + "_" + i, "");
				boolean isRegex = prefs.getBoolean(PREF_HIGHLIGHT_IS_REGEX + oldGroup + "_" + i, false);

				prefs.put(PREF_HIGHLIGHT_PATTERN + newGroup + "_" + i, pattern);
				prefs.put(PREF_HIGHLIGHT_COLOR + newGroup + "_" + i, color);
				prefs.putBoolean(PREF_HIGHLIGHT_IS_REGEX + newGroup + "_" + i, isRegex);

				// Remove old keys
				prefs.remove(PREF_HIGHLIGHT_PATTERN + oldGroup + "_" + i);
				prefs.remove(PREF_HIGHLIGHT_COLOR + oldGroup + "_" + i);
				prefs.remove(PREF_HIGHLIGHT_IS_REGEX + oldGroup + "_" + i);
			}
			prefs.remove(PREF_HIGHLIGHT_COUNT + oldGroup);

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
			// Remove from preferences
			Preferences prefs = Preferences.userNodeForPackage(MainController.class);
			int count = prefs.getInt(PREF_HIGHLIGHT_COUNT + groupToDelete, 0);
			for (int i = 0; i < count; i++) {
				prefs.remove(PREF_HIGHLIGHT_PATTERN + groupToDelete + "_" + i);
				prefs.remove(PREF_HIGHLIGHT_COLOR + groupToDelete + "_" + i);
				prefs.remove(PREF_HIGHLIGHT_IS_REGEX + groupToDelete + "_" + i);
			}
			prefs.remove(PREF_HIGHLIGHT_COUNT + groupToDelete);

			ignoreGroupChange = true;
			try {
				highlightGroupComboBox.getItems().remove(groupToDelete);
				currentGroup = "Default";
				highlightGroupComboBox.getSelectionModel().select(currentGroup);
				loadHighlights();
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
			ListView<LogLine> logListView = createLogListView(logItems);

			Tab tab = new Tab();
			Label tabLabel = new Label(file.getName());
			Button closeBtn = new Button("×");
			closeBtn.setStyle("-fx-background-color: transparent; -fx-padding: 0 0 0 5; -fx-cursor: hand;");
			closeBtn.setOnAction(_ -> tabPane.getTabs().remove(tab));
			HBox graphic = new HBox(tabLabel, closeBtn);
			graphic.setAlignment(Pos.CENTER);
			tab.setGraphic(graphic);
			tab.setClosable(false);
			tab.setContent(logListView);

			graphic.setOnMouseReleased(event -> {
				if (event.getButton() == MouseButton.MIDDLE) {
					tabPane.getTabs().remove(tab);
				}
			});
			TabState state = new TabState(file, logModel, logItems, logListView);
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

			Preferences prefs = Preferences.userNodeForPackage(MainController.class);
			prefs.put(PREF_LAST_OPEN_FILE, file.getAbsolutePath());
		} catch (IOException e) {
			showError("Could not open file: " + e.getMessage());
		}
	}

	private ListView<LogLine> createLogListView(VirtualLogList logItems) {
		ListView<LogLine> logListView = new ListView<>(logItems);
		logListView.setCellFactory(new Callback<>() {
			@Override
			public ListCell<LogLine> call(ListView<LogLine> param) {
				return new ListCell<>() {
					@Override
					protected void updateItem(LogLine item, boolean empty) {
						super.updateItem(item, empty);
						if (empty || item == null) {
							setText(null);
							setStyle("");
						} else {
							String formattedLine = String.format("%5d: %s", item.lineNumber(), item.content());
							setText(formattedLine);
							setStyle("");
							for (HighlightRule rule : highlightRules) {
								if (rule.matches(item.content())) {
									String color = rule.color();
									setStyle("-fx-background-color: " + color + ";");
									break;
								}
							}
						}
					}
				};
			}
		});
		return logListView;
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
			updateHighlightsBar();
			TabState state = getActiveTabState();
			if (state != null) {
				state.logListView.refresh();
			}
		}
	}

	@FXML
	private void handleClearHighlights() {
		highlightRules.clear();
		saveHighlights();
		updateHighlightsBar();
		TabState state = getActiveTabState();
		if (state != null) {
			state.logListView.refresh();
		}
	}

	private void updateHighlightsBar() {
		TabState state = getActiveTabState();
		if (state == null || state.logModel == null || highlightRules.isEmpty()) {
			highlightsPane.getChildren().clear();
			return;
		}

		List<HighlightRule> currentRules = new ArrayList<>(highlightRules);
		new Thread(() -> {
			int[] counts = new int[currentRules.size()];
			try {
				state.logModel.iterateLines((line, _) -> {
					if (line == null)
						return true;
					for (int j = 0; j < currentRules.size(); j++) {
						counts[j] += currentRules.get(j).countOccurrences(line);
					}
					return true;
				});
			} catch (IOException e) {
				// ignore
			}

			Platform.runLater(() -> {
				highlightsPane.getChildren().clear();
				for (int i = 0; i < currentRules.size(); i++) {
					HighlightRule rule = currentRules.get(i);
					int count = counts[i];

					HBox highlightTag = new HBox(5);
					highlightTag.setAlignment(Pos.CENTER_LEFT);
					String baseStyle = "-fx-background-color: "
							+ rule.color()
							+ "; -fx-padding: 8 12 8 12; -fx-background-radius: 4; -fx-cursor: hand; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.2), 3, 0, 0, 1);";
					highlightTag.setStyle(baseStyle);

					Label label = new Label(rule.pattern() + " (" + count + ")");
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
						updateHighlightsBar();
						TabState currentState = getActiveTabState();
						if (currentState != null) {
							currentState.logListView.refresh();
						}
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
			});
		}).start();
	}

	private void showOccurrences(HighlightRule rule) {
		TabState state = getActiveTabState();
		if (state == null || state.logModel == null)
			return;

		new Thread(() -> {
			List<LogLine> occurrences = new ArrayList<>();
			try {
				state.logModel.iterateLines((line, lineNumber) -> {
					if (line != null && rule.matches(line)) {
						occurrences.add(new LogLine(lineNumber + 1, line));
					}
					return true;
				});
			} catch (IOException e) {
				Platform.runLater(() -> showError("Error searching for occurrences: " + e.getMessage()));
				return;
			}

			Platform.runLater(() -> {
				if (occurrences.isEmpty()) {
					Alert alert = new Alert(Alert.AlertType.INFORMATION);
					alert.setTitle("No Occurrences");
					alert.setHeaderText(null);
					alert.setContentText("No occurrences found for highlight rule: " + rule.pattern());
					alert.showAndWait();
					return;
				}

				ListView<LogLine> listView = new ListView<>(FXCollections.observableArrayList(occurrences));
				listView.setCellFactory(_ -> new ListCell<>() {
					@Override
					protected void updateItem(LogLine item, boolean empty) {
						super.updateItem(item, empty);
						if (empty || item == null) {
							setText(null);
						} else {
							setText(String.format("%5d: %s", item.lineNumber(), item.content()));
						}
					}
				});

				Stage stage = new Stage();
				stage.setTitle("Occurrences for: " + rule.pattern());

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

				VBox layout = new VBox(10);
				layout.setStyle("-fx-padding: 10;");
				Label header = new Label("Found " + occurrences.size() + " occurrences. Double-click to jump to line.");
				header.setStyle("-fx-font-weight: bold;");
				VBox.setVgrow(listView, javafx.scene.layout.Priority.ALWAYS);
				layout.getChildren().addAll(header, listView);

				Scene scene = new Scene(layout, 800, 600);
				stage.setScene(scene);
				stage.show();
			});
		}).start();
	}

	@FXML
	private void handleExit() {
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
