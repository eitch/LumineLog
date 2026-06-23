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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class LogFileModel {

	private static final Logger log = LoggerFactory.getLogger(LogFileModel.class);

	private final Path filePath;
	private Object fileIdentity;
	private long[] lineOffsets = new long[1024];
	private int lineCount = 0;
	private long lastProcessedPosition = 0;
	private final ByteBuffer indexSearchBuffer = ByteBuffer.allocateDirect(16384);

	public LogFileModel(Path filePath) throws IOException {
		this.filePath = filePath;
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
		try (RandomAccessFile raf = new RandomAccessFile(this.filePath.toFile(), "r")) {
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
	}

	public Path getFilePath() {
		return this.filePath;
	}

	public long getFileSize() throws IOException {
		return Files.size(this.filePath);
	}

	public long getLastModified() throws IOException {
		return Files.getLastModifiedTime(this.filePath).toMillis();
	}

	public synchronized void updateIndex() throws IOException {
		reopenIfReplaced();

		try (RandomAccessFile raf = new RandomAccessFile(this.filePath.toFile(), "r")) {
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
	}

	public int getLineCount() {
		return lineCount;
	}

	public synchronized String getLine(int index) throws IOException {
		if (index < 0 || index >= lineCount)
			return null;
		Batch batch = readBatch(index);
		return batch.getLines(this).get(0);
	}

	public synchronized void iterateLines(int startLine, LineProcessor processor) throws IOException {
		int currentLine = startLine;
		while (currentLine < lineCount) {
			Batch batch = readBatch(currentLine);
			List<String> lines = batch.getLines(this);
			if (lines.isEmpty())
				break;

			for (String line : lines) {
				if (!processor.process(line, currentLine++))
					return;
			}
		}
	}

	public record Batch(byte[] bytes, int startLine, int endLine, long startOffset, long totalBytesToRead) {
		public List<String> getLines(LogFileModel model) {
			List<String> lines = new ArrayList<>();
			if (totalBytesToRead > 0) {
				for (int i = startLine; i < endLine; i++) {
					long relativeStart = model.lineOffsets[i] - startOffset;
					long relativeEnd = (i + 1 < model.lineCount) ? model.lineOffsets[i + 1] - startOffset : totalBytesToRead;
					int length = (int) (relativeEnd - relativeStart);
					if (length <= 0) {
						lines.add("");
					} else {
						lines.add(model.sanitizeLine(new String(bytes, (int) relativeStart, length)));
					}
				}
			} else {
				for (int i = startLine; i < endLine; i++) {
					lines.add("");
				}
			}
			return lines;
		}
	}

	public Batch readBatch(int startLine) throws IOException {
		int batchSize = 1000;
		long maxBatchBytes = 1024 * 1024; // 1MB

		try (RandomAccessFile raf = new RandomAccessFile(this.filePath.toFile(), "r")) {
			long fileSize = raf.length();
			long startOffset = lineOffsets[startLine];
			int currentEndLine = startLine;
			long batchBytes = 0;

			while (currentEndLine < lineCount && (currentEndLine - startLine) < batchSize && batchBytes < maxBatchBytes) {
				long lineStart = lineOffsets[currentEndLine];
				long lineEnd = (currentEndLine + 1 < lineCount) ? lineOffsets[currentEndLine + 1] : fileSize;
				long lineLength = lineEnd - lineStart;
				if (batchBytes + lineLength > maxBatchBytes && currentEndLine > startLine) {
					break;
				}
				batchBytes += lineLength;
				currentEndLine++;
			}

			long endOffset = (currentEndLine < lineCount) ? lineOffsets[currentEndLine] : fileSize;
			long totalBytesToRead = endOffset - startOffset;

			byte[] bytes = new byte[0];
			if (totalBytesToRead > 0) {
				bytes = new byte[(int) totalBytesToRead];
				raf.seek(startOffset);
				raf.readFully(bytes);
			}

			return new Batch(bytes, startLine, currentEndLine, startOffset, totalBytesToRead);
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

	public interface LineProcessor {
		boolean process(String line, int lineNumber);
	}
}
