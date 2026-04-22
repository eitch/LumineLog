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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public class LogFileModel implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(LogFileModel.class);

	private final Path filePath;
	private RandomAccessFile raf;
	private Object fileIdentity;
	private long[] lineOffsets = new long[1024];
	private int lineCount = 0;
	private long lastProcessedPosition = 0;
	private final ByteBuffer indexSearchBuffer = ByteBuffer.allocateDirect(16384);

	public LogFileModel(Path filePath) throws IOException {
		this.filePath = filePath;
		this.raf = new RandomAccessFile(filePath.toFile(), "r");
		this.fileIdentity = readFileIdentity();
		indexFile();
	}

	private Object readFileIdentity() throws IOException {
		BasicFileAttributes attrs = Files.readAttributes(this.filePath, BasicFileAttributes.class);
		Object fileKey = attrs.fileKey();
		if (fileKey != null)
			return fileKey;
		return attrs.lastModifiedTime().toMillis() + ":" + attrs.size();
	}

	private void reopenIfReplaced() throws IOException {
		Object currentIdentity = readFileIdentity();
		if (Objects.equals(this.fileIdentity, currentIdentity))
			return;

		log.warn("Reopening file {} due to replacement", this.filePath);
		this.raf.close();
		this.raf = new RandomAccessFile(this.filePath.toFile(), "r");
		this.fileIdentity = currentIdentity;
		indexFile();
	}

	private void addOffset(long offset) {
		if (lineCount == lineOffsets.length) {
			int newSize = (int) (lineOffsets.length * 1.5);
			long[] newOffsets = new long[newSize];
			System.arraycopy(lineOffsets, 0, newOffsets, 0, lineOffsets.length);
			lineOffsets = newOffsets;
		}
		lineOffsets[lineCount++] = offset;
	}

	private void indexFile() throws IOException {
		lineCount = 0;
		addOffset(0L);
		long fileSize = raf.length();

		FileChannel fileChannel = raf.getChannel();
		long currentPos = 0;
		while (currentPos < fileSize) {
			indexSearchBuffer.clear();
			int read = fileChannel.read(indexSearchBuffer, currentPos);
			if (read <= 0)
				break;
			indexSearchBuffer.flip();
			for (int i = 0; i < read; i++) {
				if (indexSearchBuffer.get(i) == '\n') {
					addOffset(currentPos + i + 1);
				}
			}
			currentPos += read;
		}
		lastProcessedPosition = fileSize;
	}

	public synchronized void updateIndex() throws IOException {
		reopenIfReplaced();

		long fileSize = raf.length();
		if (fileSize < lastProcessedPosition) {
			// File truncated
			lineCount = 0;
			addOffset(0L);
			lastProcessedPosition = 0;
		}

		if (fileSize == lastProcessedPosition)
			return;

		FileChannel channel = raf.getChannel();
		long currentPos = lastProcessedPosition;
		while (currentPos < fileSize) {
			indexSearchBuffer.clear();
			int read = channel.read(indexSearchBuffer, currentPos);
			if (read <= 0)
				break;
			indexSearchBuffer.flip();
			for (int i = 0; i < read; i++) {
				if (indexSearchBuffer.get(i) == '\n') {
					addOffset(currentPos + i + 1);
				}
			}
			currentPos += read;
		}
		lastProcessedPosition = fileSize;
	}

	public int getLineCount() {
		return lineCount;
	}

	public synchronized String getLine(int index) throws IOException {
		if (index < 0 || index >= lineCount)
			return null;
		long start = lineOffsets[index];
		long end = (index + 1 < lineCount) ? lineOffsets[index + 1] : -1;

		return getLine(raf, start, end);
	}

	private String getLine(RandomAccessFile raf, long start, long end) throws IOException {
		FileChannel channel = raf.getChannel();
		if (end == -1)
			end = channel.size();

		long length = end - start;
		if (length <= 0)
			return "";

		// Memory map the line or just read it
		MappedByteBuffer out = channel.map(FileChannel.MapMode.READ_ONLY, start, length);
		byte[] bytes = new byte[(int) length];
		out.get(bytes);
		String line = new String(bytes);
		if (line.endsWith("\n")) {
			line = line.substring(0, line.length() - 1);
		}
		if (line.endsWith("\r")) {
			line = line.substring(0, line.length() - 1);
		}
		return line;
	}

	public synchronized void iterateLines(int startLine, LineProcessor processor) throws IOException {
		long fileSize = raf.length();
		if (fileSize == 0 || startLine >= lineCount)
			return;

		FileChannel channel = raf.getChannel();
		ByteBuffer buffer = ByteBuffer.allocateDirect(128 * 1024);
		long bufferStartPos = -1;

		for (int i = startLine; i < lineCount; i++) {
			long start = lineOffsets[i];
			long end = (i + 1 < lineCount) ? lineOffsets[i + 1] : fileSize;
			long length = end - start;

			if (length <= 0) {
				if (!processor.process("", i))
					break;
				continue;
			}

			if (length > buffer.capacity()) {
				byte[] bytes = new byte[(int) length];
				channel.read(ByteBuffer.wrap(bytes), start);
				String line = sanitizeLine(new String(bytes));
				if (!processor.process(line, i))
					break;
				continue;
			}

			// Refill if not in buffer or not enough remaining
			if (bufferStartPos == -1 || start < bufferStartPos || start + length > bufferStartPos + buffer.limit()) {
				buffer.clear();
				int read = channel.read(buffer, start);
				if (read <= 0)
					break;
				buffer.flip();
				bufferStartPos = start;
			}

			buffer.position((int) (start - bufferStartPos));
			byte[] bytes = new byte[(int) length];
			buffer.get(bytes);

			String line = sanitizeLine(new String(bytes));
			if (!processor.process(line, i)) {
				break;
			}
		}
	}

	private String sanitizeLine(String line) {
		if (line.endsWith("\n")) {
			line = line.substring(0, line.length() - 1);
		}
		if (line.endsWith("\r")) {
			line = line.substring(0, line.length() - 1);
		}
		return line;
	}

	public synchronized void iterateLines(LineProcessor processor) throws IOException {
		iterateLines(0, processor);
	}

	@Override
	public void close() throws IOException {
		if (raf != null) {
			raf.close();
			raf = null;
		}
	}

	public interface LineProcessor {
		boolean process(String line, int lineNumber);
	}
}
