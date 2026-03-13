package ch.eitchnet.luminelog.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VirtualLogListTest {

	@TempDir
	Path tempDir;

	@Test
	public void testVirtualList() throws IOException {
		Path logFile = tempDir.resolve("test.log");
		Files.writeString(logFile, "Line 1\nLine 2\nLine 3\n");

		LogFileModel model = new LogFileModel(logFile);
		VirtualLogList list = new VirtualLogList(model);

		assertEquals(4, list.size());
		assertEquals("Line 1", list.get(0).content());
		assertEquals(1, list.get(0).lineNumber());
		assertEquals("Line 2", list.get(1).content());
		assertEquals(2, list.get(1).lineNumber());
		assertEquals("Line 3", list.get(2).content());
		assertEquals(3, list.get(2).lineNumber());
		assertEquals("", list.get(3).content());
		assertEquals(4, list.get(3).lineNumber());

		// Test change notification
		AtomicBoolean changed = new AtomicBoolean(false);
		list.addListener((javafx.collections.ListChangeListener<LogLine>) _ -> changed.set(true));

		Files.writeString(logFile, "Line 4\n", java.nio.file.StandardOpenOption.APPEND);
		model.updateIndex();
		list.fireSizeChanged(4, 5);

		assertTrue(changed.get());
		assertEquals(5, list.size());
		assertEquals("Line 4", list.get(3).content());
		assertEquals("", list.get(4).content());
	}

	@Test
	public void testVirtualListNoTrailingNewline() throws IOException {
		Path logFile = tempDir.resolve("test2.log");
		Files.writeString(logFile, "Line 1\nLine 2\nLine 3"); // No trailing newline

		LogFileModel model = new LogFileModel(logFile);
		VirtualLogList list = new VirtualLogList(model);

		assertEquals(3, list.size());
		assertEquals("Line 1", list.get(0).content());
		assertEquals("Line 2", list.get(1).content());
		assertEquals("Line 3", list.get(2).content());
	}

	@Test
	public void testTruncation() throws IOException {
		Path logFile = tempDir.resolve("trunc.log");
		Files.writeString(logFile, "Line 1\nLine 2\nLine 3\n");

		LogFileModel model = new LogFileModel(logFile);
		VirtualLogList list = new VirtualLogList(model);

		assertEquals(4, list.size());

		// Truncate
		Files.writeString(logFile, "New Line 1\n");
		model.updateIndex();
		list.fireSizeChanged(4, 2);

		assertEquals(2, list.size());
		assertEquals("New Line 1", list.get(0).content());
		assertEquals(1, list.get(0).lineNumber());
		assertEquals("", list.get(1).content());
	}
}
