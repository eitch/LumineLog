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

public class MainController {

	private static final String PREF_LAST_OPEN_FILE = "lastOpenFile";

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
	private javafx.scene.layout.FlowPane highlightsPane;

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
		highlightColorPicker.setValue(Color.YELLOW);
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
			tab.setGraphic(tabLabel);
			tab.setContent(logListView);

			tabLabel.setOnMouseReleased(event -> {
				if (event.getButton() == MouseButton.MIDDLE) {
					tabPane.getTabs().remove(tab);
				}
			});
			TabState state = new TabState(file, logModel, logItems, logListView);
			tab.setUserData(state);
			tab.setOnClosed(_ -> {
				System.out.println("Closing tab for file " + file.getAbsolutePath());
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
