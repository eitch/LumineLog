package ch.eitchnet.tail4j.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LogFileModelTest {

	@TempDir
	Path tempDir;

	@Test
	public void testIndexingAndTailing() throws IOException {
		Path logFile = tempDir.resolve("test.log");
		Files.writeString(logFile, "Line 1\nLine 2\n");

		LogFileModel model = new LogFileModel(logFile);
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

	@Test
	public void testLargeFile() throws IOException {
		Path logFile = tempDir.resolve("large.log");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 10000; i++) {
			sb.append("Line ").append(i).append("\n");
		}
		Files.writeString(logFile, sb.toString());

		LogFileModel model = new LogFileModel(logFile);
		assertEquals(10001, model.getLineCount());
		assertEquals("Line 0", model.getLine(0));
		assertEquals("Line 9999", model.getLine(9999));
		assertEquals("", model.getLine(10000));
	}
}
