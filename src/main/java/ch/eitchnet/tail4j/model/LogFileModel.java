package ch.eitchnet.tail4j.model;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LogFileModel implements AutoCloseable {
	private final Path filePath;
	private RandomAccessFile raf;
	private final List<Long> lineOffsets = new ArrayList<>();
	private long lastProcessedPosition = 0;

	public LogFileModel(Path filePath) throws IOException {
		this.filePath = filePath;
		this.raf = new RandomAccessFile(filePath.toFile(), "r");
		indexFile();
	}

	private void indexFile() throws IOException {
		lineOffsets.clear();
		lineOffsets.add(0L);
		long fileSize = raf.length();

		ByteBuffer buffer = ByteBuffer.allocate(8192);
		FileChannel fileChannel = raf.getChannel();
		long currentPos = 0;
		while (currentPos < fileSize) {
			buffer.clear();
			int read = fileChannel.read(buffer, currentPos);
			if (read <= 0)
				break;
			buffer.flip();
			for (int i = 0; i < read; i++) {
				if (buffer.get(i) == '\n') {
					lineOffsets.add(currentPos + i + 1);
				}
			}
			currentPos += read;
		}
		lastProcessedPosition = fileSize;
	}

	public synchronized void updateIndex() throws IOException {
		long fileSize = raf.length();
		if (fileSize < lastProcessedPosition) {
			// File truncated
			lineOffsets.clear();
			lineOffsets.add(0L);
			lastProcessedPosition = 0;
		}

		if (fileSize == lastProcessedPosition)
			return;

		ByteBuffer buffer = ByteBuffer.allocate(8192);
		FileChannel channel = raf.getChannel();
		long currentPos = lastProcessedPosition;
		while (currentPos < fileSize) {
			buffer.clear();
			int read = channel.read(buffer, currentPos);
			if (read <= 0)
				break;
			buffer.flip();
			for (int i = 0; i < read; i++) {
				if (buffer.get(i) == '\n') {
					lineOffsets.add(currentPos + i + 1);
				}
			}
			currentPos += read;
		}
		lastProcessedPosition = fileSize;
	}

	public int getLineCount() {
		return lineOffsets.size();
	}

	public synchronized String getLine(int index) throws IOException {
		if (index < 0 || index >= lineOffsets.size())
			return null;
		long start = lineOffsets.get(index);
		long end = (index + 1 < lineOffsets.size()) ? lineOffsets.get(index + 1) : -1;

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

	public synchronized void iterateLines(LineProcessor processor) throws IOException {
		for (int i = 0; i < lineOffsets.size(); i++) {
			long start = lineOffsets.get(i);
			long end = (i + 1 < lineOffsets.size()) ? lineOffsets.get(i + 1) : -1;
			String line = getLine(raf, start, end);
			if (!processor.process(line, i)) {
				break;
			}
		}
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
