package ch.eitchnet.tail4j;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Controller for the log viewer with search and highlighting capabilities
 */
public class LogViewerController {
	@FXML
	private TextField filePathField;
	@FXML
	private Button openButton;
	@FXML
	private TextField searchField;
	@FXML
	private CheckBox regexCheckBox;
	@FXML
	private CheckBox followTailCheckBox;
	@FXML
	private Button clearSearchButton;
	@FXML
	private ListView<String> logListView;
	@FXML
	private Label statusLabel;

	private LogFileReader logFileReader;
	private FileWatcher fileWatcher;
	private String currentSearchText = "";
	private boolean isRegexSearch = false;
	private Pattern searchPattern = null;
	private ObservableList<String> logLines = FXCollections.observableArrayList();

	@FXML
	public void initialize() {
		// Set up ListView with custom cell factory
		logListView.setItems(logLines);
		logListView.setCellFactory(lv -> new HighlightedLogCell());

		searchField.textProperty().addListener((obs, oldVal, newVal) -> performSearch(newVal));
		regexCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
			isRegexSearch = newVal;
			performSearch(searchField.getText());
		});
		followTailCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
			if (newVal) {
				startWatching();
			} else {
				stopWatching();
			}
		});
	}

	@FXML
	private void handleOpenFile() {
		FileChooser fileChooser = new FileChooser();
		fileChooser.setTitle("Open Log File");
		fileChooser.getExtensionFilters().addAll(
				new FileChooser.ExtensionFilter("Log Files", "*.log", "*.txt"),
				new FileChooser.ExtensionFilter("All Files", "*.*")
		);

		File file = fileChooser.showOpenDialog(openButton.getScene().getWindow());
		if (file != null) {
			openFile(file.toPath());
		}
	}

	private void openFile(Path filePath) {
		closeCurrentFile();

		try {
			logFileReader = new LogFileReader(filePath);
			logFileReader.open();

			filePathField.setText(filePath.toString());
			loadFile();

			if (followTailCheckBox.isSelected()) {
				startWatching();
			}

			statusLabel.setText("Loaded: " + filePath.getFileName());
		} catch (IOException e) {
			showError("Failed to open file: " + e.getMessage());
		}
	}

	private void loadFile() {
		if (logFileReader == null) return;

		try {
			List<String> lines = logFileReader.readAllLines();
			logLines.clear();
			logLines.addAll(lines);
			statusLabel.setText("Loaded " + lines.size() + " lines");

			// Force layout refresh and scroll to bottom
			if (!logLines.isEmpty()) {
				scrollToBottom();
			}
		} catch (IOException e) {
			showError("Failed to read file: " + e.getMessage());
		}
	}

	private void scrollToBottom() {
		if (logLines.isEmpty()) return;

		Platform.runLater(() -> {
			logListView.scrollTo(logLines.size() - 1);
		});
	}

	private void startWatching() {
		if (logFileReader == null || fileWatcher != null) return;

		try {
			fileWatcher = new FileWatcher(logFileReader.getFilePath(), this::onFileModified);
			fileWatcher.start();
			statusLabel.setText("Watching file for changes...");
		} catch (IOException e) {
			showError("Failed to start file watcher: " + e.getMessage());
			followTailCheckBox.setSelected(false);
		}
	}

	private void stopWatching() {
		if (fileWatcher != null) {
			fileWatcher.stop();
			fileWatcher = null;
			statusLabel.setText("Stopped watching file");
		}
	}

	private void onFileModified(Path path) {
		if (logFileReader == null) return;

		try {
			List<String> newLines = logFileReader.readNewLines();

			// Handle incomplete line - update the last line if it was incomplete
			if (logFileReader.hasIncompleteLine() && !logLines.isEmpty()) {
				// Remove the old incomplete line and add new lines
				logLines.removeLast();
			}

			if (!newLines.isEmpty()) {
				logLines.addAll(newLines);
			}

			// Add current incomplete line if exists
			if (logFileReader.hasIncompleteLine()) {
				logLines.add(logFileReader.getIncompleteLine());
			}

			if (followTailCheckBox.isSelected() && !logLines.isEmpty()) {
				// Auto-scroll to bottom
				scrollToBottom();
			}
		} catch (IOException e) {
			Platform.runLater(() -> showError("Failed to read new lines: " + e.getMessage()));
		}
	}

	@FXML
	private void handleClearSearch() {
		searchField.clear();
	}

	private void performSearch(String searchText) {
		currentSearchText = searchText == null ? "" : searchText;

		if (currentSearchText.isEmpty()) {
			searchPattern = null;
		} else {
			try {
				if (isRegexSearch) {
					searchPattern = Pattern.compile(currentSearchText);
				} else {
					searchPattern = Pattern.compile(Pattern.quote(currentSearchText), Pattern.CASE_INSENSITIVE);
				}
			} catch (PatternSyntaxException e) {
				statusLabel.setText("Invalid regex pattern");
				searchPattern = null;
			}
		}

		// Update cell factory to apply new search pattern
		updateCellFactory();
		logListView.refresh();
	}

	private void updateCellFactory() {
		logListView.setCellFactory(lv -> {
			HighlightedLogCell cell = new HighlightedLogCell();
			cell.setSearchPattern(searchPattern, true);
			return cell;
		});
	}

	private void closeCurrentFile() {
		stopWatching();

		if (logFileReader != null) {
			try {
				logFileReader.close();
			} catch (IOException e) {
				// Ignore
			}
			logFileReader = null;
		}

		logLines.clear();
		filePathField.clear();
	}

	private void showError(String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setTitle("Error");
		alert.setHeaderText(null);
		alert.setContentText(message);
		alert.showAndWait();
		statusLabel.setText("Error: " + message);
	}

	public void shutdown() {
		closeCurrentFile();
	}
}
