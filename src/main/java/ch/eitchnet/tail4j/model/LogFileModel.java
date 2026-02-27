package ch.eitchnet.tail4j.model;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LogFileModel {
    private final Path filePath;
    private FileChannel fileChannel;
    private final List<Long> lineOffsets = new ArrayList<>();
    private long lastProcessedPosition = 0;

    public LogFileModel(Path filePath) throws IOException {
        this.filePath = filePath;
        indexFile();
    }

    private void indexFile() throws IOException {
        lineOffsets.clear();
        lineOffsets.add(0L);
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            this.fileChannel = raf.getChannel();
            long fileSize = fileChannel.size();
            
            // For indexing, we might not need MappedByteBuffer for the whole file at once if it's huge,
            // but for simplicity let's assume we can map chunks or use a buffer.
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long currentPos = 0;
            while (currentPos < fileSize) {
                buffer.clear();
                int read = fileChannel.read(buffer, currentPos);
                if (read <= 0) break;
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
    }

    public synchronized void updateIndex() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            FileChannel channel = raf.getChannel();
            long fileSize = channel.size();
            if (fileSize < lastProcessedPosition) {
                // File truncated
                lineOffsets.clear();
                lineOffsets.add(0L);
                lastProcessedPosition = 0;
            }

            if (fileSize == lastProcessedPosition) return;
            
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long currentPos = lastProcessedPosition;
            while (currentPos < fileSize) {
                buffer.clear();
                int read = channel.read(buffer, currentPos);
                if (read <= 0) break;
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
    }

    public int getLineCount() {
        return lineOffsets.size();
    }

    public String getLine(int index) throws IOException {
        if (index < 0 || index >= lineOffsets.size()) return null;
        long start = lineOffsets.get(index);
        long end = (index + 1 < lineOffsets.size()) ? lineOffsets.get(index + 1) : -1;
        
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            FileChannel channel = raf.getChannel();
            if (end == -1) end = channel.size();
            
            long length = end - start;
            if (length <= 0) return "";
            
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
    }
}
