package ch.eitchnet.tail4j.controller;

import ch.eitchnet.tail4j.model.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.scene.Scene;
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
    private ListView<LogLine> logListView;
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

    private LogFileModel logModel;
    private VirtualLogList logItems;
    private final List<HighlightRule> highlightRules = new ArrayList<>();
    private Timer tailTimer;

    @FXML
    public void initialize() {
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
        highlightColorPicker.setValue(Color.YELLOW);

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
        File file = fileChooser.showOpenDialog(logListView.getScene().getWindow());
        if (file != null) {
            openFile(file);
        }
    }

    private void openFile(File file) {
        if (tailTimer != null) {
            tailTimer.cancel();
        }
        try {
            logModel = new LogFileModel(file.toPath());
            logItems = new VirtualLogList(logModel);
            logListView.setItems(logItems);
            statusLabel.setText("File: " + file.getAbsolutePath());
            updateHighlightsBar();
            refreshLogView();
            startTailing();

            Preferences prefs = Preferences.userNodeForPackage(MainController.class);
            prefs.put(PREF_LAST_OPEN_FILE, file.getAbsolutePath());
        } catch (IOException e) {
            showError("Could not open file: " + e.getMessage());
        }
    }

    private void refreshLogView() {
        if (logItems != null) {
            int lineCount = logModel.getLineCount();
            logItems.fireSizeChanged(0, lineCount);
            if (lineCount > 0) {
                logListView.scrollTo(lineCount - 1);
            }
        }
    }

    private void startTailing() {
        tailTimer = new Timer(true);
        tailTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (tailCheckBox.isSelected() && logModel != null) {
                    try {
                        int oldCount = logModel.getLineCount();
                        logModel.updateIndex();
                        int newCount = logModel.getLineCount();
                        if (newCount != oldCount) {
                            Platform.runLater(() -> {
                                logItems.fireSizeChanged(oldCount, newCount);
                                updateHighlightsBar();
                                if (newCount > oldCount) {
                                    logListView.scrollTo(newCount - 1);
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
            logListView.refresh();
        }
    }

    @FXML
    private void handleClearHighlights() {
        highlightRules.clear();
        updateHighlightsBar();
        logListView.refresh();
    }

    private void updateHighlightsBar() {
        if (logModel == null || highlightRules.isEmpty()) {
            highlightsPane.getChildren().clear();
            return;
        }

        List<HighlightRule> currentRules = new ArrayList<>(highlightRules);
        new Thread(() -> {
            int[] counts = new int[currentRules.size()];
            try {
                logModel.iterateLines((line, lineNumber) -> {
                    if (line == null) return true;
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
                    highlightTag.setStyle("-fx-background-color: " + rule.color() + "; -fx-padding: 2 5 2 5; -fx-background-radius: 3; -fx-cursor: hand;");

                    Label label = new Label(rule.pattern() + " (" + count + ")");
                    
                    Button dismissBtn = new Button("×");
                    dismissBtn.setStyle("-fx-background-color: transparent; -fx-padding: 0 2 0 2; -fx-font-weight: bold;");
                    dismissBtn.setTooltip(new Tooltip("Remove highlight"));
                    dismissBtn.setOnAction(event -> {
                        highlightRules.remove(rule);
                        updateHighlightsBar();
                        logListView.refresh();
                    });

                    highlightTag.getChildren().addAll(label, dismissBtn);

                    highlightTag.setOnMouseClicked(event -> {
                        if (event.getTarget() == dismissBtn) return;
                        showOccurrences(rule);
                    });
                    Tooltip.install(highlightTag, new Tooltip("Click to show all occurrences"));

                    highlightsPane.getChildren().add(highlightTag);
                }
            });
        }).start();
    }

    private void showOccurrences(HighlightRule rule) {
        if (logModel == null) return;

        new Thread(() -> {
            List<LogLine> occurrences = new ArrayList<>();
            try {
                logModel.iterateLines((line, lineNumber) -> {
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
                listView.setCellFactory(param -> new ListCell<>() {
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
                            logListView.getSelectionModel().select(index);
                            logListView.scrollTo(index);
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
        if (tailTimer != null) tailTimer.cancel();
        Platform.exit();
    }

    private String toWebColor(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
