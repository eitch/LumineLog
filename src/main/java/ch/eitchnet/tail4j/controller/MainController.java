package ch.eitchnet.tail4j.controller;

import ch.eitchnet.tail4j.model.HighlightRule;
import ch.eitchnet.tail4j.model.LogFileModel;
import ch.eitchnet.tail4j.model.LogLine;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
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

    private LogFileModel logModel;
    private final List<HighlightRule> highlightRules = new ArrayList<>();
    private final ObservableList<LogLine> logItems = FXCollections.observableArrayList();
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
            statusLabel.setText("File: " + file.getAbsolutePath());
            refreshLogView();
            startTailing();
        } catch (IOException e) {
            showError("Could not open file: " + e.getMessage());
        }
    }

    private void refreshLogView() {
        logItems.clear();
        int lineCount = logModel.getLineCount();
        // For very large files, we might not want to add all to logItems if it's not virtualized enough
        // but ListView's observable list handles it reasonably well until a certain point.
        // However, BareTail-like behavior means we want to see the tail.
        
        // Let's just load the last 1000 lines initially to be safe if it's huge
        int start = Math.max(0, lineCount - 1000);
        for (int i = start; i < lineCount; i++) {
            try {
                logItems.add(new LogLine(i + 1, logModel.getLine(i)));
            } catch (IOException e) {
                logItems.add(new LogLine(i + 1, "ERROR READING LINE " + i));
            }
        }
        logListView.setItems(logItems);
        if (!logItems.isEmpty()) {
            logListView.scrollTo(logItems.size() - 1);
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
                        if (newCount > oldCount) {
                            List<LogLine> newLines = new ArrayList<>();
                            for (int i = oldCount; i < newCount; i++) {
                                newLines.add(new LogLine(i + 1, logModel.getLine(i)));
                            }
                            Platform.runLater(() -> {
                                logItems.addAll(newLines);
                                // Limit items to prevent memory issues if tailing forever
                                if (logItems.size() > 5000) {
                                    logItems.remove(0, logItems.size() - 5000);
                                }
                                logListView.scrollTo(logItems.size() - 1);
                            });
                        } else if (newCount < oldCount) {
                            // File was truncated
                            Platform.runLater(() -> refreshLogView());
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
            logListView.refresh();
        }
    }

    @FXML
    private void handleClearHighlights() {
        highlightRules.clear();
        logListView.refresh();
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
