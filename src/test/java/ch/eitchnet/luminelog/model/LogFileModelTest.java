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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LogFileModelTest {

	private static final Logger log = LoggerFactory.getLogger(LogFileModelTest.class);
	@TempDir
	Path tempDir;

	@Test
	public void testOversizedLineRejectedBeforePayloadRead() throws IOException {
		Path logFile = tempDir.resolve("oversized.log");
		try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "rw")) {
			file.setLength(LogFileModel.MAX_ENCODED_LINE_BYTES + 1);
		}
		LogFileModel model = new LogFileModel(logFile);
		assertEquals(1, model.getLineCount());
		LogFileModel.OversizedLineException failure = assertThrows(LogFileModel.OversizedLineException.class,
				() -> model.snapshot().readBatch(0));
		assertEquals(0, failure.lineNumber());
		assertEquals(LogFileModel.MAX_ENCODED_LINE_BYTES + 1, failure.byteLength());
		assertEquals(LogFileModel.MAX_ENCODED_LINE_BYTES, failure.limit());
		assertTrue(failure.getMessage().contains(logFile.toString()));
		assertTrue(failure.getMessage().contains("including CR/LF"));
		assertThrows(LogFileModel.OversizedLineException.class, () -> model.readBatch(0));
		assertThrows(LogFileModel.OversizedLineException.class, () -> model.getLine(0));
	}

	@Test
	public void testNormalRowsBeforeAndAfterOversizedLineRemainReadable() throws IOException {
		Path logFile = tempDir.resolve("mixed-oversized.log");
		try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "rw")) {
			file.writeBytes("before\n");
			file.seek(file.getFilePointer() + LogFileModel.MAX_ENCODED_LINE_BYTES);
			file.writeBytes("\nafter\n");
		}
		LogFileModel model = new LogFileModel(logFile);
		LogFileModel.Batch first = model.readBatch(0);
		assertEquals(List.of("before"), first.getLines());
		assertEquals(1, first.endLine());
		assertEquals(7, first.totalBytesToRead());
		assertEquals("before", model.getLine(0));
		LogFileModel.OversizedLineException failure = assertThrows(LogFileModel.OversizedLineException.class,
				() -> model.readBatch(1));
		assertEquals(1, failure.lineNumber());
		assertEquals(LogFileModel.MAX_ENCODED_LINE_BYTES + 1, failure.byteLength());
		assertEquals(List.of("after", ""), model.readBatch(2).getLines());
		List<String> processed = new ArrayList<>();
		assertThrows(LogFileModel.OversizedLineException.class, () -> model.iterateLines((line, _) -> {
			processed.add(line);
			return true;
		}));
		assertEquals(List.of("before"), processed);
	}

	@Test
	public void testUtf8EncodedBoundaryIncludesLineTerminators() throws IOException {
		Path logFile = tempDir.resolve("utf8-boundary.log");
		int limit = LogFileModel.MAX_ENCODED_LINE_BYTES;
		for (String terminator : List.of("", "\n", "\r\n")) {
			for (int size : List.of(limit - 1, limit, limit + 1)) {
				int payloadBytes = size - terminator.length();
				String content = "€😀".repeat(payloadBytes / 7) + "x".repeat(payloadBytes % 7);
				Files.writeString(logFile, content + terminator, StandardCharsets.UTF_8);
				assertEquals(size, Files.size(logFile));
				LogFileModel model = new LogFileModel(logFile);
				if (size > limit) {
					LogFileModel.OversizedLineException failure = assertThrows(LogFileModel.OversizedLineException.class,
							() -> model.readBatch(0));
					assertEquals(size, failure.byteLength());
				} else {
					LogFileModel.Batch batch = model.readBatch(0);
					assertEquals(content, batch.getLines().getFirst());
					assertTrue(content.length() <= LogFileModel.MAX_DECODED_LINE_CHARS);
					assertTrue(batch.bytes().length <= LogFileModel.MAX_BATCH_BYTES);
				}
				if (!terminator.isEmpty())
					assertEquals(List.of(""), model.readBatch(1).getLines());
			}
		}
	}

	@Test
	public void testAsciiAndMalformedUtf8DecodedCharBoundary() throws IOException {
		Path logFile = tempDir.resolve("decoded-boundary.log");
		byte[] bytes = new byte[LogFileModel.MAX_ENCODED_LINE_BYTES];
		for (byte value : new byte[]{'a', (byte) 0xff}) {
			java.util.Arrays.fill(bytes, value);
			Files.write(logFile, bytes);
			String decoded = new LogFileModel(logFile).getLine(0);
			assertEquals(LogFileModel.MAX_DECODED_LINE_CHARS, decoded.length());
			assertEquals(value == 'a' ? 'a' : '\ufffd', decoded.charAt(decoded.length() - 1));
		}
	}

	@Test
	public void testEmptyCrLfAndTrailingNewlineRows() throws IOException {
		Path logFile = tempDir.resolve("empty-rows.log");
		Files.writeString(logFile, "");
		assertEquals(List.of(""), new LogFileModel(logFile).readBatch(0).getLines());
		Files.writeString(logFile, "\r\n\n€😀\r\n");
		assertEquals(List.of("", "", "€😀", ""), new LogFileModel(logFile).readBatch(0).getLines());
	}

	@Test
	public void testBatchByteAndRowBudgets() throws IOException {
		Path logFile = tempDir.resolve("batch-boundary.log");
		String halfBatchLine = "a".repeat(LogFileModel.MAX_BATCH_BYTES / 2 - 1) + "\n";
		Files.writeString(logFile, halfBatchLine.repeat(2) + "tail");
		LogFileModel model = new LogFileModel(logFile);
		LogFileModel.Batch first = model.readBatch(0);
		assertEquals(2, first.endLine());
		assertEquals(LogFileModel.MAX_BATCH_BYTES, first.totalBytesToRead());
		assertEquals(List.of("tail"), model.readBatch(2).getLines());
		Files.writeString(logFile, "x" + halfBatchLine + halfBatchLine);
		model.updateIndex();
		assertEquals(1, model.readBatch(0).endLine());
		assertEquals(halfBatchLine.length() + 1, model.readBatch(0).totalBytesToRead());
		assertEquals(halfBatchLine.substring(0, halfBatchLine.length() - 1), model.getLine(1));
		Files.writeString(logFile, "\n".repeat(LogFileModel.MAX_BATCH_LINES));
		model.updateIndex();
		assertEquals(LogFileModel.MAX_BATCH_LINES, model.readBatch(0).getLines().size());
		assertEquals(List.of(""), model.readBatch(LogFileModel.MAX_BATCH_LINES).getLines());
	}

	@Test
	public void testPublicBatchConstructorGuardsBeforeDecodeAndRetainsCloning() {
		byte[] oversized = new byte[LogFileModel.MAX_BATCH_BYTES + 1];
		assertThrows(IllegalArgumentException.class,
				() -> new LogFileModel.Batch(oversized, 0, 1, 0, oversized.length));
		assertThrows(IllegalArgumentException.class,
				() -> new LogFileModel.Batch(new byte[0], 0, LogFileModel.MAX_BATCH_LINES + 1, 0, 0));
		assertThrows(IllegalArgumentException.class,
				() -> new LogFileModel.Batch(new byte[0], Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0));
		assertThrows(IllegalArgumentException.class,
				() -> new LogFileModel.Batch(new byte[0], 1, 0, 0, 0));
		assertThrows(IllegalArgumentException.class,
				() -> new LogFileModel.Batch(new byte[0], 0, 1, 0, LogFileModel.MAX_BATCH_BYTES + 1));
		byte[] bytes = "é\r\n".getBytes(StandardCharsets.UTF_8);
		LogFileModel.Batch batch = new LogFileModel.Batch(bytes, 0, 2, 0, bytes.length);
		bytes[0] = 0;
		batch.bytes()[0] = 0;
		assertNotEquals(0, batch.bytes()[0]);
		assertEquals(List.of("é", ""), batch.getLines());
		assertThrows(UnsupportedOperationException.class, () -> batch.getLines().add("changed"));
		byte[] boundary = new byte[LogFileModel.MAX_BATCH_BYTES];
		assertEquals(LogFileModel.MAX_DECODED_LINE_CHARS,
				new LogFileModel.Batch(boundary, 0, 1, 0, boundary.length).getLines().getFirst().length());
	}

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

		LogFileModel model = new LogFileModel(logFile);
		assertEquals(50001, model.getLineCount());
		assertEquals("Line 0", model.getLine(0));
		assertEquals("Line 9999", model.getLine(9999));
		assertEquals("", model.getLine(50000));
	}

	@Test
	public void testIterateLines() throws IOException {
		Path logFile = tempDir.resolve("iterate.log");
		Files.writeString(logFile, "Line 1\nLine 2\r\nLine 3");

		List<String> iteratedLines;
		LogFileModel model = new LogFileModel(logFile);
		iteratedLines = new ArrayList<>();
		model.iterateLines((line, _) -> {
			iteratedLines.add(line);
			return true;
		});

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
		LogFileModel model = new LogFileModel(logFile);
		model.iterateLines(2, (line, _) -> {
			iteratedLines.add(line);
			return true;
		});

		assertEquals(2, iteratedLines.size());
		assertEquals("Line 3", iteratedLines.get(0));
		assertEquals("Line 4", iteratedLines.get(1));
	}

	@Test
	public void testUpdateIndexDetectsReplacedFile() throws IOException {
		Path logFile = tempDir.resolve("replace.log");
		Files.writeString(logFile, "Old 1\nOld 2\n");

		LogFileModel model = new LogFileModel(logFile);
		assertEquals(3, model.getLineCount());
		assertEquals("Old 1", model.getLine(0));
		assertEquals("Old 2", model.getLine(1));

		Path replacement = tempDir.resolve("replace-new.log");
		Files.writeString(replacement, "New 1\nNew 2\n");
		try {
			Files.move(replacement, logFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException _) {
			Files.move(replacement, logFile, StandardCopyOption.REPLACE_EXISTING);
		}

		model.updateIndex();

		assertEquals(3, model.getLineCount());
		assertEquals("New 1", model.getLine(0));
		assertEquals("New 2", model.getLine(1));
		assertEquals("", model.getLine(2));
	}

	@Test
	public void testBatchSurvivesAppendAndTruncate() throws IOException {
		Path logFile = tempDir.resolve("batch.log");
		Files.writeString(logFile, "first\npartial");
		LogFileModel model = new LogFileModel(logFile);
		LogFileModel.Batch batch = model.readBatch(0);
		Files.writeString(logFile, " completed\nnext", StandardOpenOption.APPEND);
		model.updateIndex();
		assertEquals(List.of("first", "partial"), batch.getLines(model));
		Files.writeString(logFile, "x");
		model.updateIndex();
		assertEquals(List.of("first", "partial"), batch.getLines(model));
	}

	@Test
	public void testSameSizeRewriteReindexesBoundaries() throws IOException {
		Path logFile = tempDir.resolve("rewrite.log");
		Files.writeString(logFile, "a\nbbb");
		LogFileModel model = new LogFileModel(logFile);
		FileTime previous = Files.getLastModifiedTime(logFile);
		Files.writeString(logFile, "aaa\nb");
		Files.setLastModifiedTime(logFile, FileTime.fromMillis(previous.toMillis() + 1000));
		model.updateIndex();
		assertEquals("aaa", model.getLine(0));
		assertEquals("b", model.getLine(1));
	}

	@Test
	public void testSnapshotFreezesPartialFinalLineAndMetadataAcrossAppend() throws IOException {
		Path logFile = tempDir.resolve("snapshot.log");
		Files.writeString(logFile, "héllo\r\npart");
		LogFileModel model = new LogFileModel(logFile);
		LogFileModel.Snapshot before = model.snapshot();
		long originalSize = Files.size(logFile);
		long originalModified = Files.getLastModifiedTime(logFile).toMillis();
		assertSame(before, model.snapshot());
		model.updateIndex();
		assertSame(before, model.snapshot());
		Files.writeString(logFile, "ial\nnew", StandardOpenOption.APPEND);
		model.updateIndex();
		assertEquals(List.of("héllo", "part"), before.readBatch(0).getLines());
		assertEquals(2, before.lineCount());
		assertEquals(originalSize, before.fileSize());
		assertEquals(originalModified, before.lastModified());
		assertEquals(before.version() + 1, model.snapshot().version());
		assertEquals(before.generation(), model.snapshot().generation());
		assertEquals(List.of("héllo", "partial", "new"), model.snapshot().readBatch(0).getLines());
	}

	@Test
	public void testPartialLineAppendChangesVersionWithoutChangingLineCount() throws IOException {
		Path logFile = tempDir.resolve("partial.log");
		Files.writeString(logFile, "part");
		LogFileModel model = new LogFileModel(logFile);
		LogFileModel.Snapshot before = model.snapshot();
		Files.writeString(logFile, "ial", StandardOpenOption.APPEND);
		model.updateIndex();
		assertEquals(before.lineCount(), model.snapshot().lineCount());
		assertEquals(before.version() + 1, model.snapshot().version());
		assertEquals(before.generation(), model.snapshot().generation());
		assertEquals(List.of("part"), before.readBatch(0).getLines());
		assertEquals("partial", model.getLine(0));
	}

	@Test
	public void testBatchIsImmutableAndIndependentOfModel() throws IOException {
		Path logFile = tempDir.resolve("immutable.log");
		Files.writeString(logFile, "日本語\nlast");
		LogFileModel model = new LogFileModel(logFile);
		LogFileModel.Batch batch = model.snapshot().readBatch(0);
		assertEquals(List.of("日本語", "last"), batch.getLines());
		assertSame(batch.getLines(), batch.getLines(model));
		assertThrows(UnsupportedOperationException.class, () -> batch.getLines().add("bad"));
		byte[] originalBytes = batch.bytes();
		originalBytes[0] = 0;
		assertNotEquals(0, batch.bytes()[0]);
		Files.delete(logFile);
		assertEquals(List.of("日本語", "last"), batch.getLines());
	}

	@Test
	public void testSameSizeRewriteWithSubMillisecondTimestamp() throws IOException {
		Path logFile = tempDir.resolve("precise.log");
		Files.writeString(logFile, "a\nbbb");
		FileTime first = FileTime.from(Instant.ofEpochSecond(1700000000, 100));
		FileTime second = FileTime.from(Instant.ofEpochSecond(1700000000, 200));
		Files.setLastModifiedTime(logFile, first);
		LogFileModel model = new LogFileModel(logFile);
		LogFileModel.Snapshot before = model.snapshot();
		Files.writeString(logFile, "aaa\nb");
		Files.setLastModifiedTime(logFile, second);
		assertNotEquals(first, Files.getLastModifiedTime(logFile));
		model.updateIndex();
		assertEquals(before.lastModified(), model.snapshot().lastModified());
		assertEquals(before.version() + 1, model.snapshot().version());
		assertEquals(before.generation() + 1, model.snapshot().generation());
		assertEquals(List.of("aaa", "b"), model.readBatch(0).getLines());
		assertThrows(IOException.class, () -> before.readBatch(0));
	}

	@Test
	public void testReplacementWithSameSizeAndTimestampChangesGeneration() throws IOException {
		Path logFile = tempDir.resolve("identity.log");
		Files.writeString(logFile, "a\nbbb");
		LogFileModel model = new LogFileModel(logFile);
		LogFileModel.Snapshot before = model.snapshot();
		FileTime modified = Files.getLastModifiedTime(logFile);
		Path replacement = tempDir.resolve("identity-new.log");
		Files.writeString(replacement, "aaa\nb");
		Files.setLastModifiedTime(replacement, modified);
		Files.move(replacement, logFile, StandardCopyOption.REPLACE_EXISTING);
		model.updateIndex();
		assertEquals(before.version() + 1, model.snapshot().version());
		assertEquals(before.generation() + 1, model.snapshot().generation());
		assertEquals(List.of("aaa", "b"), model.readBatch(0).getLines());
		assertThrows(IOException.class, () -> before.readBatch(0));
	}

	@Test
	public void testTruncateAndRegrowPastPreviousSizeReindexes() throws IOException {
		Path logFile = tempDir.resolve("regrow.log");
		Files.writeString(logFile, "old\ncontent");
		LogFileModel model = new LogFileModel(logFile);
		LogFileModel.Snapshot before = model.snapshot();
		Files.writeString(logFile, "new content\nlonger final line");
		model.updateIndex();
		assertEquals(before.generation() + 1, model.snapshot().generation());
		assertEquals(before.version() + 1, model.snapshot().version());
		assertEquals(List.of("new content", "longer final line"), model.readBatch(0).getLines());
		assertThrows(IOException.class, () -> before.readBatch(0));
	}

	@Test
	public void testTruncatedSnapshotReadFailsWithIOExceptionAndBatchRemainsUsable() throws IOException {
		Path logFile = tempDir.resolve("short.log");
		Files.writeString(logFile, "one\ntwo\nthree");
		LogFileModel model = new LogFileModel(logFile);
		LogFileModel.Snapshot before = model.snapshot();
		LogFileModel.Batch batch = before.readBatch(0);
		Files.writeString(logFile, "x");
		assertThrows(IOException.class, () -> before.readBatch(2));
		assertThrows(IOException.class, () -> model.readBatch(2));
		assertThrows(IOException.class, () -> before.readBatch(-1));
		assertThrows(IOException.class, () -> before.readBatch(before.lineCount()));
		model.updateIndex();
		assertEquals(before.generation() + 1, model.snapshot().generation());
		assertEquals(List.of("one", "two", "three"), batch.getLines());
	}

	@Test
	public void testSnapshotsAcrossOffsetChunks() throws IOException {
		Path logFile = tempDir.resolve("chunks.log");
		Files.writeString(logFile, "line\n".repeat(32760) + "partial");
		LogFileModel model = new LogFileModel(logFile);
		List<LogFileModel.Snapshot> snapshots = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			snapshots.add(model.snapshot());
			Files.writeString(logFile, "\nnext", StandardOpenOption.APPEND);
			model.updateIndex();
		}
		for (int i = 0; i < snapshots.size(); i++) {
			LogFileModel.Snapshot captured = snapshots.get(i);
			assertEquals(32761 + i, captured.lineCount());
			assertEquals(List.of(i == 0 ? "partial" : "next"),
					captured.readBatch(captured.lineCount() - 1).getLines());
			assertEquals("line", captured.readBatch(1023).getLines().get(0));
		}
	}

	@Test
	public void testIterationDoesNotHoldMonitorAndKeepsSnapshotAcrossBatches() throws IOException {
		Path logFile = tempDir.resolve("iteration-snapshot.log");
		Files.writeString(logFile, "line\n".repeat(1100) + "partial");
		LogFileModel model = new LogFileModel(logFile);
		List<String> lines = new ArrayList<>();
		model.iterateLines((line, number) -> {
			assertFalse(Thread.holdsLock(model));
			lines.add(line);
			if (number == 0) {
				try {
					Files.writeString(logFile, " completed\nextra", StandardOpenOption.APPEND);
					model.updateIndex();
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			}
			return true;
		});
		assertEquals(1101, lines.size());
		assertEquals("partial", lines.getLast());
		List<Integer> processed = new ArrayList<>();
		model.iterateLines((_, number) -> {
			processed.add(number);
			return false;
		});
		assertEquals(List.of(0), processed);
	}

	@Test
	public void testEmptyAndTrailingNewlineSemantics() throws IOException {
		Path logFile = tempDir.resolve("empty.log");
		Files.writeString(logFile, "");
		LogFileModel model = new LogFileModel(logFile);
		assertEquals(1, model.snapshot().lineCount());
		assertEquals(List.of(""), model.snapshot().readBatch(0).getLines());
		Files.writeString(logFile, "\r\n\n", StandardOpenOption.APPEND);
		model.updateIndex();
		assertEquals(List.of("", "", ""), model.readBatch(0).getLines());
		assertTrue(model.getLine(-1) == null && model.getLine(3) == null);
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
