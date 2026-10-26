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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class LogFileModel {

	/** Encoded line limit, including any CR/LF terminators. */
	public static final int MAX_ENCODED_LINE_BYTES = 1024 * 1024;
	/** UTF-8 decoding produces at most one UTF-16 code unit per encoded byte. */
	public static final int MAX_DECODED_LINE_CHARS = MAX_ENCODED_LINE_BYTES;
	public static final int MAX_BATCH_BYTES = 1024 * 1024;
	public static final int MAX_BATCH_LINES = 1000;

	private final Path filePath;
	private Snapshot current;
	private final ByteBuffer indexSearchBuffer = ByteBuffer.allocateDirect(16384);

	public LogFileModel(Path filePath) throws IOException {
		this.filePath = filePath;
		updateIndex();
	}

	private static Object identity(BasicFileAttributes attrs) {
		Object fileKey = attrs.fileKey();
		return fileKey != null ? fileKey : attrs.creationTime();
	}

	public Path getFilePath() {
		return this.filePath;
	}

	public synchronized long getFileSize() throws IOException {
		return Files.size(this.filePath);
	}

	public synchronized long getLastModified() throws IOException {
		return Files.getLastModifiedTime(this.filePath).toMillis();
	}

	public synchronized Snapshot snapshot() {
		return current;
	}

	private static byte[] readTail(RandomAccessFile raf, long size) throws IOException {
		byte[] tail = new byte[(int) Math.min(4096, size)];
		raf.seek(size - tail.length);
		raf.readFully(tail);
		return tail;
	}

	public synchronized void updateIndex() throws IOException {
		BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
		Object fileIdentity = identity(attrs);
		long fileSize = attrs.size();
		boolean rebuild = current == null || !Objects.equals(current.fileIdentity, fileIdentity)
				|| fileSize < current.fileSize
				|| (fileSize == current.fileSize && !attrs.lastModifiedTime().equals(current.modified));
		try (RandomAccessFile raf = new RandomAccessFile(this.filePath.toFile(), "r")) {
			if (!rebuild && fileSize > current.fileSize)
				rebuild = !Arrays.equals(current.tail, readTail(raf, current.fileSize));
			if (!rebuild && fileSize == current.fileSize)
				return;

			OffsetBuilder offsets = new OffsetBuilder(rebuild ? null : current.offsets);
			if (rebuild)
				offsets.add(0);
			long currentPos = rebuild ? 0 : current.fileSize;
			FileChannel channel = raf.getChannel();
			while (currentPos < fileSize) {
				indexSearchBuffer.clear();
				indexSearchBuffer.limit((int) Math.min(indexSearchBuffer.capacity(), fileSize - currentPos));
				int read = channel.read(indexSearchBuffer, currentPos);
				if (read <= 0)
					throw new IOException("File shortened while indexing " + filePath);
				indexSearchBuffer.flip();
				for (int i = 0; i < read; i++) {
					if (indexSearchBuffer.get(i) == '\n')
						offsets.add(currentPos + i + 1);
				}
				currentPos += read;
			}
			byte[] tail = readTail(raf, fileSize);
			BasicFileAttributes after = Files.readAttributes(filePath, BasicFileAttributes.class);
			if (!Objects.equals(fileIdentity, identity(after)) || after.size() < fileSize
					|| (after.size() == fileSize && !attrs.lastModifiedTime().equals(after.lastModifiedTime())))
				throw new IOException("File changed while indexing " + filePath);
			long version = current == null ? 1 : current.version + 1;
			long generation = current == null ? 1 : current.generation + (rebuild ? 1 : 0);
			current = new Snapshot(filePath, offsets.build(), fileSize, attrs.lastModifiedTime(), fileIdentity,
					version, generation, tail);
		}
	}

	public synchronized int getLineCount() {
		return current.lineCount();
	}

	public synchronized String getLine(int index) throws IOException {
		if (index < 0 || index >= current.lineCount())
			return null;
		return current.readBatch(index).getLines().get(0);
	}

	public void iterateLines(int startLine, LineProcessor processor) throws IOException {
		Snapshot captured = snapshot();
		if (startLine < 0)
			throw new IOException("Negative start line: " + startLine);
		int currentLine = startLine;
		while (currentLine < captured.lineCount()) {
			for (String line : captured.readBatch(currentLine).getLines()) {
				if (!processor.process(line, currentLine++))
					return;
			}
		}
	}

	public synchronized Batch readBatch(int startLine) throws IOException {
		return current.readBatch(startLine);
	}

	public static final class OversizedLineException extends IOException {
		private final int lineNumber;
		private final long byteLength;
		private final int limit;

		private OversizedLineException(Path filePath, int lineNumber, long byteLength, int limit) {
			super("Line " + ((long) lineNumber + 1) + " in " + filePath + " is " + byteLength
					+ " encoded bytes; limit is " + limit + " bytes including CR/LF. Split the line before reading.");
			this.lineNumber = lineNumber;
			this.byteLength = byteLength;
			this.limit = limit;
		}

		/** Zero-based line index, consistent with readBatch and getLine. */
		public int lineNumber() { return lineNumber; }
		public long byteLength() { return byteLength; }
		public int limit() { return limit; }
	}

	/** Stable index boundaries, not a historical copy of the underlying file contents. */
	public static final class Snapshot {
		private final Path filePath;
		private final OffsetIndex offsets;
		private final long fileSize;
		private final FileTime modified;
		private final Object fileIdentity;
		private final long version;
		private final long generation;
		private final byte[] tail;

		private Snapshot(Path filePath, OffsetIndex offsets, long fileSize, FileTime modified, Object fileIdentity,
				long version, long generation, byte[] tail) {
			this.filePath = filePath;
			this.offsets = offsets;
			this.fileSize = fileSize;
			this.modified = modified;
			this.fileIdentity = fileIdentity;
			this.version = version;
			this.generation = generation;
			this.tail = tail;
		}

		public long version() { return version; }
		public long generation() { return generation; }
		public int lineCount() { return offsets.count; }
		public long fileSize() { return fileSize; }
		public long lastModified() { return modified.toMillis(); }

		private long endOffset(int line) {
			return line + 1 < lineCount() ? offsets.get(line + 1) : fileSize;
		}

		private void validate(RandomAccessFile raf) throws IOException {
			BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
			if (!Objects.equals(fileIdentity, identity(attrs)) || attrs.size() < fileSize
					|| (attrs.size() == fileSize && !modified.equals(attrs.lastModifiedTime()))
					|| !Arrays.equals(tail, readTail(raf, fileSize)))
				throw new IOException("Snapshot no longer available for " + filePath);
		}

		public Batch readBatch(int startLine) throws IOException {
			if (startLine < 0 || startLine >= lineCount())
				throw new IOException("Line outside snapshot: " + startLine);
			long startOffset = offsets.get(startLine);
			int endLine = startLine;
			long endOffset = startOffset;
			while (endLine < lineCount() && endLine - startLine < MAX_BATCH_LINES) {
				long nextEnd = endOffset(endLine);
				long lineBytes = nextEnd - endOffset;
				if (lineBytes < 0 || nextEnd > fileSize)
					throw new IOException("Invalid snapshot boundaries for " + filePath);
				if (lineBytes > MAX_ENCODED_LINE_BYTES) {
					if (endLine == startLine)
						throw new OversizedLineException(filePath, endLine, lineBytes, MAX_ENCODED_LINE_BYTES);
					break;
				}
				if (nextEnd - startOffset > MAX_BATCH_BYTES)
					break;
				endOffset = nextEnd;
				endLine++;
				if (endOffset - startOffset >= MAX_BATCH_BYTES)
					break;
			}
			long totalBytesToRead = endOffset - startOffset;
			if (totalBytesToRead < 0 || totalBytesToRead > MAX_BATCH_BYTES)
				throw new IOException("Batch exceeds read limit in " + filePath);
			byte[] bytes = new byte[(int) totalBytesToRead];
			try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
				validate(raf);
				raf.seek(startOffset);
				raf.readFully(bytes);
				validate(raf);
			}
			List<String> decoded = new ArrayList<>();
			for (int line = startLine; line < endLine; line++) {
				long start = offsets.get(line) - startOffset;
				long end = endOffset(line) - startOffset;
				if (start < 0 || end < start || end > bytes.length)
					throw new IOException("Invalid snapshot boundaries for " + filePath);
				decoded.add(sanitizeLine(new String(bytes, (int) start, (int) (end - start), StandardCharsets.UTF_8)));
			}
			return new Batch(bytes, startLine, endLine, startOffset, totalBytesToRead, decoded);
		}
	}

	public static final class Batch {
		private final byte[] bytes;
		private final int startLine;
		private final int endLine;
		private final long startOffset;
		private final long totalBytesToRead;
		private final List<String> lines;

		public Batch(byte[] bytes, int startLine, int endLine, long startOffset, long totalBytesToRead) {
			this(bytes, startLine, endLine, startOffset, totalBytesToRead,
					decodeLines(bytes, (long) endLine - startLine, totalBytesToRead));
		}

		private Batch(byte[] bytes, int startLine, int endLine, long startOffset, long totalBytesToRead,
				List<String> lines) {
			this.bytes = bytes.clone();
			this.startLine = startLine;
			this.endLine = endLine;
			this.startOffset = startOffset;
			this.totalBytesToRead = totalBytesToRead;
			this.lines = List.copyOf(lines);
		}

		private static List<String> decodeLines(byte[] bytes, long count, long totalBytesToRead) {
			if (bytes.length > MAX_BATCH_BYTES || bytes.length > MAX_ENCODED_LINE_BYTES
					|| totalBytesToRead != bytes.length)
				throw new IllegalArgumentException("Batch must contain at most " + MAX_BATCH_BYTES
						+ " encoded bytes and totalBytesToRead must match bytes.length");
			if (count < 0 || count > MAX_BATCH_LINES)
				throw new IllegalArgumentException("Batch must contain between 0 and " + MAX_BATCH_LINES + " lines");
			List<String> decoded = new ArrayList<>();
			int start = 0;
			for (int line = 0; line < count; line++) {
				int end = start;
				while (end < bytes.length && bytes[end++] != '\n') {
				}
				decoded.add(sanitizeLine(new String(bytes, start, end - start, StandardCharsets.UTF_8)));
				start = end;
			}
			return decoded;
		}

		public byte[] bytes() { return bytes.clone(); }
		public int startLine() { return startLine; }
		public int endLine() { return endLine; }
		public long startOffset() { return startOffset; }
		public long totalBytesToRead() { return totalBytesToRead; }
		public List<String> getLines() { return lines; }
		public List<String> getLines(LogFileModel model) { return getLines(); }
	}

	// Immutable directory paths share full chunks; only the active chunk is copied on append.
	private record OffsetIndex(Object[] root, int count) {
		private long[] chunk(int index) {
			Object[] node = root;
			for (int shift = 20; shift > 0; shift -= 5)
				node = (Object[]) node[(index >>> shift) & 31];
			return (long[]) node[index & 31];
		}

		private long get(int index) {
			return chunk(index >>> 10)[index & 1023];
		}
	}

	private static final class OffsetBuilder {
		private Object[] root;
		private int count;
		private long[] activeChunk;

		private OffsetBuilder(OffsetIndex previous) {
			root = previous == null ? new Object[32] : previous.root;
			count = previous == null ? 0 : previous.count;
		}

		private static Object[] put(Object[] node, int shift, int index, long[] chunk) {
			Object[] copy = node == null ? new Object[32] : node.clone();
			int slot = (index >>> shift) & 31;
			copy[slot] = shift == 0 ? chunk : put((Object[]) copy[slot], shift - 5, index, chunk);
			return copy;
		}

		private void add(long offset) throws IOException {
			if (count == Integer.MAX_VALUE)
				throw new IOException("Too many lines to index");
			if (activeChunk == null || (count & 1023) == 0) {
				activeChunk = (count & 1023) == 0 ? new long[1024]
						: new OffsetIndex(root, count).chunk(count >>> 10).clone();
				root = put(root, 20, count >>> 10, activeChunk);
			}
			activeChunk[count++ & 1023] = offset;
		}

		private OffsetIndex build() {
			return new OffsetIndex(root, count);
		}
	}

	private static String sanitizeLine(String line) {
		if (line.endsWith("\n")) {
			line = line.substring(0, line.length() - 1);
		}
		if (line.endsWith("\r")) {
			line = line.substring(0, line.length() - 1);
		}
		return line;
	}

	public void iterateLines(LineProcessor processor) throws IOException {
		iterateLines(0, processor);
	}

	public interface LineProcessor {
		boolean process(String line, int lineNumber);
	}
}
