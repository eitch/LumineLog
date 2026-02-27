package ch.eitchnet.tail4j;

import javafx.scene.control.ListCell;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Custom ListCell that renders log lines with search highlighting
 */
public class HighlightedLogCell extends ListCell<String> {
	private final TextFlow textFlow;
	private Pattern searchPattern;
	private boolean dimNonMatching;

	public HighlightedLogCell() {
		this.textFlow = new TextFlow();
		textFlow.setStyle("-fx-font-family: 'Monospace', 'Courier New', monospace; -fx-font-size: 12px;");
		setGraphic(textFlow);
	}

	public void setSearchPattern(Pattern pattern, boolean dimNonMatching) {
		this.searchPattern = pattern;
		this.dimNonMatching = dimNonMatching;
	}

	@Override
	protected void updateItem(String line, boolean empty) {
		super.updateItem(line, empty);

		if (empty || line == null) {
			textFlow.getChildren().clear();
			setGraphic(null);
		} else {
			textFlow.getChildren().clear();

			if (searchPattern == null) {
				// No search pattern, just display the line
				Text text = new Text(line);
				textFlow.getChildren().add(text);
			} else {
				// Apply highlighting
				try {
					Matcher matcher = searchPattern.matcher(line);
					int lastEnd = 0;
					boolean hasMatch = false;

					while (matcher.find()) {
						hasMatch = true;

						// Add text before match
						if (matcher.start() > lastEnd) {
							Text beforeText = new Text(line.substring(lastEnd, matcher.start()));
							if (dimNonMatching) {
								beforeText.setFill(Color.BLACK);
							}
							textFlow.getChildren().add(beforeText);
						}

						// Add highlighted match
						Text matchText = new Text(matcher.group());
						matchText.setFill(Color.BLACK);
						matchText.setStyle("-fx-background-color: yellow; -fx-font-weight: bold;");
						textFlow.getChildren().add(matchText);

						lastEnd = matcher.end();
					}

					// Add remaining text
					if (lastEnd < line.length()) {
						Text remainingText = new Text(line.substring(lastEnd));
						if (dimNonMatching) {
							remainingText.setFill(Color.BLACK);
						}
						textFlow.getChildren().add(remainingText);
					}

					// Dim non-matching lines
					if (!hasMatch && dimNonMatching) {
						Text text = new Text(line);
						text.setFill(Color.GRAY);
						textFlow.getChildren().clear();
						textFlow.getChildren().add(text);
					}

				} catch (Exception e) {
					// Fallback to plain text on error
					Text text = new Text(line);
					textFlow.getChildren().add(text);
				}
			}

			setGraphic(textFlow);
		}
	}
}
