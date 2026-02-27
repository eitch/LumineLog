package ch.eitchnet.tail4j.controller;

import ch.eitchnet.tail4j.model.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.Callback;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainController {

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
                        jumpToNextOccurrence(rule);
                    });
                    Tooltip.install(highlightTag, new Tooltip("Click to jump to next occurrence"));

                    highlightsPane.getChildren().add(highlightTag);
                }
            });
        }).start();
    }

    private void jumpToNextOccurrence(HighlightRule rule) {
        if (logModel == null) return;

        int currentIndex = logListView.getSelectionModel().getSelectedIndex();
        int lineCount = logModel.getLineCount();
        int startIndex = (currentIndex + 1) % lineCount;

        for (int i = 0; i < lineCount; i++) {
            int index = (startIndex + i) % lineCount;
            try {
                String line = logModel.getLine(index);
                if (line != null && rule.matches(line)) {
                    logListView.getSelectionModel().select(index);
                    logListView.scrollTo(index);
                    return;
                }
            } catch (IOException e) {
                // ignore
            }
        }
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
