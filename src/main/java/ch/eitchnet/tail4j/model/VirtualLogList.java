package ch.eitchnet.tail4j.model;

import javafx.collections.ObservableListBase;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class VirtualLogList extends ObservableListBase<LogLine> {

    private final LogFileModel logModel;
    private final Map<Integer, LogLine> cache;
    private static final int CACHE_SIZE = 1000;

    public VirtualLogList(LogFileModel logModel) {
        this.logModel = logModel;
        this.cache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, LogLine> eldest) {
                return size() > CACHE_SIZE;
            }
        };
    }

    @Override
    public LogLine get(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
        }

        return cache.computeIfAbsent(index, i -> {
            try {
                String content = logModel.getLine(i);
                return new LogLine(i + 1, content != null ? content : "");
            } catch (IOException e) {
                return new LogLine(i + 1, "ERROR READING LINE " + i + ": " + e.getMessage());
            }
        });
    }

    @Override
    public int size() {
        return logModel.getLineCount();
    }

    public void fireSizeChanged(int oldSize, int newSize) {
        if (newSize > oldSize) {
            // Remove the previous last entry from cache if it was an empty line due to trailing newline
            // because now it might have content.
            if (oldSize > 0) {
                cache.remove(oldSize - 1);
            }
            beginChange();
            nextAdd(oldSize, newSize);
            endChange();
        } else if (newSize < oldSize) {
            // Probably truncated
            cache.clear();
            beginChange();
            nextReplace(0, oldSize, java.util.Collections.emptyList());
            nextAdd(0, newSize);
            endChange();
        }
    }
}
