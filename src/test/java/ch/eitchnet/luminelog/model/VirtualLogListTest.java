/*
 * Copyright (c) 2026 Robert von Burg <eitch@eitchnet.ch>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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

	@Test
	public void testTruncationWithSameLineCountClearsCache() throws IOException {
		Path logFile = tempDir.resolve("trunc_same_count.log");
		Files.writeString(logFile, "Line 1\nLine 2\n");

		LogFileModel model = new LogFileModel(logFile);
		VirtualLogList list = new VirtualLogList(model);

		assertEquals(3, list.size());
		assertEquals("Line 1", list.get(0).content());
		assertEquals("Line 2", list.get(1).content());

		// Truncate and rewrite with the same number of lines.
		Files.writeString(logFile, "New 1\nNew 2\n");
		model.updateIndex();
		list.fireSizeChanged(3, 3);

		assertEquals(3, list.size());
		assertEquals("New 1", list.get(0).content());
		assertEquals("New 2", list.get(1).content());
		assertEquals("", list.get(2).content());
	}
}
