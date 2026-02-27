package ch.eitchnet.tail4j;

import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Watches a file for changes and notifies listeners in real-time.
 * Enables the tail -f functionality.
 */
public class FileWatcher {
	private final Path filePath;
	private final Consumer<Path> onFileModified;
	private WatchService watchService;
	private ExecutorService executor;
	private volatile boolean watching = false;

	public FileWatcher(Path filePath, Consumer<Path> onFileModified) {
		this.filePath = filePath;
		this.onFileModified = onFileModified;
	}

	/**
	 * Start watching the file for modifications
	 */
	public void start() throws IOException {
		if (watching) {
			return;
		}

		Path directory = filePath.getParent();
		watchService = FileSystems.getDefault().newWatchService();
		directory.register(watchService, ENTRY_MODIFY);

		watching = true;
		executor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "FileWatcher-" + filePath.getFileName());
			t.setDaemon(true);
			return t;
		});

		executor.submit(this::watchLoop);
	}

	/**
	 * Stop watching the file
	 */
	public void stop() {
		watching = false;
		if (executor != null) {
			executor.shutdownNow();
		}
		if (watchService != null) {
			try {
				watchService.close();
			} catch (IOException e) {
				// Ignore
			}
		}
	}

	private void watchLoop() {
		while (watching) {
			WatchKey key;
			try {
				key = watchService.take();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (ClosedWatchServiceException e) {
				break;
			}

			for (WatchEvent<?> event : key.pollEvents()) {
				WatchEvent.Kind<?> kind = event.kind();

				if (kind == StandardWatchEventKinds.OVERFLOW) {
					continue;
				}

				@SuppressWarnings("unchecked")
				WatchEvent<Path> ev = (WatchEvent<Path>) event;
				Path filename = ev.context();

				// Check if the modified file is the one we're watching
				if (filename.equals(filePath.getFileName())) {
					Platform.runLater(() -> onFileModified.accept(filePath));
				}
			}

			boolean valid = key.reset();
			if (!valid) {
				break;
			}
		}
	}

	public boolean isWatching() {
		return watching;
	}
}
