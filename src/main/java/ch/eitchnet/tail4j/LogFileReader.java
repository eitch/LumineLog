package ch.eitchnet.tail4j;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Off-heap log file reader that can handle files of any size. Uses memory-mapped files and direct buffers for efficient
 * reading.
 */
public class LogFileReader {
	private static final int BUFFER_SIZE = 8192; // 8KB buffer
	private final Path filePath;
	private RandomAccessFile randomAccessFile;
	private FileChannel fileChannel;
	private long filePointer = 0;
	private StringBuilder incompleteLine = new StringBuilder(); // Track incomplete line at end of previous read

	public LogFileReader(Path filePath) {
		this.filePath = filePath;
	}

	public void open() throws IOException {
		randomAccessFile = new RandomAccessFile(filePath.toFile(), "r");
		fileChannel = randomAccessFile.getChannel();
		filePointer = 0;
	}

	public void close() throws IOException {
		if (fileChannel != null) {
			fileChannel.close();
		}
		if (randomAccessFile != null) {
			randomAccessFile.close();
		}
	}

	/**
	 * Read lines from the current position up to maxLines
	 */
	public List<String> readLines(int maxLines) throws IOException {
		List<String> lines = new ArrayList<>();
		ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
		StringBuilder currentLine = new StringBuilder(incompleteLine);
		incompleteLine.setLength(0); // Clear for this read

		fileChannel.position(filePointer);

		while (lines.size() < maxLines && fileChannel.read(buffer) != -1) {
			buffer.flip();

			byte[] bytes = new byte[buffer.remaining()];
			buffer.get(bytes);
			String chunk = new String(bytes, StandardCharsets.UTF_8);

			for (char c : chunk.toCharArray()) {
				if (c == '\n') {
					lines.add(currentLine.toString());
					currentLine.setLength(0);
					if (lines.size() >= maxLines) {
						break;
					}
				} else if (c != '\r') {
					currentLine.append(c);
				}
			}

			buffer.clear();
		}

		// Store incomplete line for next read
		incompleteLine.append(currentLine);
		filePointer = fileChannel.position();

		return lines;
	}

	/**
	 * Read all lines from the file
	 */
	public List<String> readAllLines() throws IOException {
		filePointer = 0;
		fileChannel.position(0);
		incompleteLine.setLength(0);
		List<String> allLines = new ArrayList<>();

		List<String> chunk;
		do {
			chunk = readLines(1000);
			allLines.addAll(chunk);
		} while (!chunk.isEmpty());

		// Add incomplete line at the end if it exists
		if (!incompleteLine.isEmpty()) {
			allLines.add(incompleteLine.toString());
			// Adjust filePointer back to before the incomplete line
			// so readNewLines() can re-read it when more data arrives
			filePointer -= incompleteLine.toString().getBytes(StandardCharsets.UTF_8).length;
		}

		return allLines;
	}

	/**
	 * Read new lines that have been appended since last read (for tail functionality)
	 */
	public List<String> readNewLines() throws IOException {
		long currentSize = fileChannel.size();
		if (filePointer >= currentSize) {
			return new ArrayList<>();
		}
		return readLines(Integer.MAX_VALUE);
	}

	/**
	 * Reset to beginning of file
	 */
	public void reset() throws IOException {
		filePointer = 0;
		fileChannel.position(0);
		incompleteLine.setLength(0);
	}

	/**
	 * Get the current incomplete line (line without newline at end of file)
	 */
	public String getIncompleteLine() {
		return incompleteLine.toString();
	}

	/**
	 * Check if there's an incomplete line
	 */
	public boolean hasIncompleteLine() {
		return !incompleteLine.isEmpty();
	}

	/**
	 * Get the current file size
	 */
	public long getFileSize() throws IOException {
		return fileChannel.size();
	}

	public Path getFilePath() {
		return filePath;
	}
}
