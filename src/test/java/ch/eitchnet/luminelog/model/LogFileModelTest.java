package ch.eitchnet.luminelog.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LogFileModelTest {

	private static final Logger log = LoggerFactory.getLogger(LogFileModelTest.class);
	@TempDir
	Path tempDir;

	@Test
	public void testIndexingAndTailing() throws IOException {
		Path logFile = tempDir.resolve("test.log");
		Files.writeString(logFile, "Line 1\nLine 2\n");

		try (LogFileModel model = new LogFileModel(logFile)) {
			// It might be 3 if it ends with \n and we count the empty space after it as a line
			// But let's see what it actually is.
			// Actually, if it ends with \n, and my code adds an offset for EVERY \n,
			// then "Line 1\nLine 2\n" has \n at index 6 and 13.
			// lineOffsets will have 0, 7, 14.
			// 14 == fileSize, so it's 3 lines.
			assertEquals(3, model.getLineCount());
			assertEquals("Line 1", model.getLine(0));
			assertEquals("Line 2", model.getLine(1));
			assertEquals("", model.getLine(2));

			Files.writeString(logFile, "Line 3\n", StandardOpenOption.APPEND);
			model.updateIndex();
			assertEquals(4, model.getLineCount());
			assertEquals("Line 3", model.getLine(2));
			assertEquals("", model.getLine(3));

			// Test truncation
			Files.writeString(logFile, "New Line 1\n");
			model.updateIndex();
			assertEquals(2, model.getLineCount());
			assertEquals("New Line 1", model.getLine(0));
			assertEquals("", model.getLine(1));
		}
	}

	@Test
	public void testLargeFile() throws IOException {
		Path logFile = tempDir.resolve("large.log");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 50000; i++) {
			if (i == 0 || i == 9999) {
				sb.append("Line ").append(i).append("\n");
			} else {
				String randomString = UUID.randomUUID().toString() + UUID.randomUUID() + UUID.randomUUID();
				sb.append("Line ").append(i).append(randomString).append("\n");
			}
		}
		Files.writeString(logFile, sb.toString());
		log.info("Wrote large file to {} with size {}MB", logFile, logFile.toFile().length() / (1024 * 1024));

		try (LogFileModel model = new LogFileModel(logFile)) {
			assertEquals(50001, model.getLineCount());
			assertEquals("Line 0", model.getLine(0));
			assertEquals("Line 9999", model.getLine(9999));
			assertEquals("", model.getLine(50000));
		}
	}

	@Test
	public void testIterateLines() throws IOException {
		Path logFile = tempDir.resolve("iterate.log");
		Files.writeString(logFile, "Line 1\nLine 2\r\nLine 3");

		List<String> iteratedLines;
		try (LogFileModel model = new LogFileModel(logFile)) {
			iteratedLines = new ArrayList<>();
			model.iterateLines((line, _) -> {
				iteratedLines.add(line);
				return true;
			});
		}

		// 0: "Line 1\n" -> 0, 7
		// 1: "Line 2\r\n" -> 7, 15
		// 2: "Line 3" -> 15, -1
		// LogFileModel indexFile/updateIndex should handle this.
		// "Line 1\n" (index 6 is \n) -> offsets: 0, 7
		// "Line 2\r\n" (index 14 is \n) -> offsets: 0, 7, 15
		// "Line 3" -> no \n, so offsets: 0, 7, 15
		// Wait, my indexFile only adds offset for \n.
		// So for "Line 1\nLine 2\r\nLine 3", offsets: 0 (start), 7 (after first \n), 15 (after second \n).
		// getLineCount = 3.
		// i=0: 0 to 7 -> "Line 1\n" -> "Line 1"
		// i=1: 7 to 15 -> "Line 2\r\n" -> "Line 2"
		// i=2: 15 to end -> "Line 3" -> "Line 3"

		assertEquals(3, iteratedLines.size());
		assertEquals("Line 1", iteratedLines.get(0));
		assertEquals("Line 2", iteratedLines.get(1));
		assertEquals("Line 3", iteratedLines.get(2));
	}

	@Test
	public void testIterateLinesFromOffset() throws IOException {
		Path logFile = tempDir.resolve("iterate_offset.log");
		Files.writeString(logFile, "Line 1\nLine 2\nLine 3\nLine 4");

		List<String> iteratedLines = new ArrayList<>();
		try (LogFileModel model = new LogFileModel(logFile)) {
			model.iterateLines(2, (line, _) -> {
				iteratedLines.add(line);
				return true;
			});
		}

		assertEquals(2, iteratedLines.size());
		assertEquals("Line 3", iteratedLines.get(0));
		assertEquals("Line 4", iteratedLines.get(1));
	}

	@Test
	public void testHighlightRuleMatches() {
		HighlightRule rule = new HighlightRule("test", "red", false);
		List<HighlightRule.MatchRange> matches = rule.findMatches("this is a test string with another test");
		assertEquals(2, matches.size());
		assertEquals(10, matches.get(0).start());
		assertEquals(14, matches.get(0).end());
		assertEquals(35, matches.get(1).start());
		assertEquals(39, matches.get(1).end());

		HighlightRule regexRule = new HighlightRule("t.st", "blue", true);
		matches = regexRule.findMatches("test tast tust");
		assertEquals(3, matches.size());
		assertEquals(0, matches.get(0).start());
		assertEquals(4, matches.get(0).end());
		assertEquals(5, matches.get(1).start());
		assertEquals(9, matches.get(1).end());
		assertEquals(10, matches.get(2).start());
		assertEquals(14, matches.get(2).end());
	}
}
