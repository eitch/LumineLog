package ch.eitchnet.luminelog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * A small utility to generate random log entries for testing purposes. It generates logs in the target/ directory and
 * truncates the file after 10MB.
 */
public class LogGenerator {

	private static final String[] LEVELS = {"INFO", "DEBUG", "WARN", "ERROR", "TRACE"};
	private static final String[] THREADS = {"main", "pool-1-thread-1", "pool-1-thread-2", "Timer-0",
			"JavaFX Application Thread"};
	private static final String[] CLASSES = {"ch.eitchnet.luminelog.controller.MainController",
			"ch.eitchnet.luminelog.model.LogFileModel", "ch.eitchnet.luminelog.util.DialogUtil",
			"ch.eitchnet.luminelog.controller.ConfigService", "ch.eitchnet.luminelog.LumineLogApplication"};
	private static final String[] METHODS = {"initialize", "handleOpen", "updateIndex", "saveConfig", "showError",
			"start", "stop"};
	private static final String[] MESSAGES = {"Starting application...", "Loading configuration from file.",
			"File not found, using defaults.", "User clicked on open file button.",
			"Indexing file: {} with size {} bytes", "Unexpected error occurred during processing.",
			"Tailing file for changes.", "Highlighting applied to {} lines.", "Memory usage is at {}%",
			"Connection established to remote server."};

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
	private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
	private static final Path LOG_FILE = Paths.get("target", "generated-test.log");

	private final Random random = new Random();

	public static void main(String[] args) throws Exception {
		LogGenerator generator = new LogGenerator();
		generator.run(args.length > 0 && args[0].equals("--once"));
	}

	public void run(boolean once) throws Exception {
		Files.createDirectories(LOG_FILE.getParent());
		System.out.println("Starting log generation in " + LOG_FILE.toAbsolutePath());
		if (!once)
			System.out.println("Press Ctrl+C to stop.");

		do {
			checkSizeAndTruncate();

			boolean burst = random.nextInt(100) > 80;
			int count = burst ? 500 + random.nextInt(1000) : 1 + random.nextInt(5);
			if (burst)
				System.out.println("Bursting with " + count + " entries");

			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < count; i++) {
				sb.append(generateRandomEntry()).append("\n");
			}

			Files.writeString(LOG_FILE, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

			if (!once)
				Thread.sleep(10 + random.nextInt(90));
		} while (!once);
	}

	private String generateRandomEntry() {
		String timestamp = LocalDateTime.now().format(FORMATTER);
		String level = LEVELS[random.nextInt(LEVELS.length)];
		String thread = THREADS[random.nextInt(THREADS.length)];
		String className = CLASSES[random.nextInt(CLASSES.length)];
		String method = METHODS[random.nextInt(METHODS.length)];
		String message = MESSAGES[random.nextInt(MESSAGES.length)];

		// Randomly add some parameters to messages
		if (message.contains("{}")) {
			message = message.replaceFirst("\\{}", String.valueOf(random.nextInt(10000)));
			if (message.contains("{}")) {
				message = message.replaceFirst("\\{}", String.valueOf(random.nextInt(1000000)));
			}
		}

		return String.format("%s [%s] %-5s %s.%s - %s", timestamp, thread, level, className, method, message);
	}

	private void checkSizeAndTruncate() throws IOException {
		if (Files.exists(LOG_FILE) && Files.size(LOG_FILE) > MAX_FILE_SIZE) {
			System.out.println("Log file reached 10MB, truncating...");
			// Simple truncation: clear the file
			Files.writeString(LOG_FILE, "", StandardOpenOption.TRUNCATE_EXISTING);
		}
	}
}
