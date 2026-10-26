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

import ch.eitchnet.luminelog.model.LogFileModel;
import ch.eitchnet.luminelog.model.VirtualLogList;
import ch.eitchnet.luminelog.service.LineLoadingService;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Compares the former synchronous miss policy with asynchronous owner-thread reads, not timings. */
public final class AsyncLineReadProbe {

	public static void main(String[] args) throws Exception {
		Path path = Files.createTempFile(Path.of("target"), "async-lines-", ".log").toAbsolutePath();
		Path synchronous = Files.createTempFile(Path.of("target"), "sync-lines-", ".jfr");
		Path asynchronous = Files.createTempFile(Path.of("target"), "async-lines-", ".jfr");
		try {
			String text = "x".repeat(255);
			try (var writer = Files.newBufferedWriter(path)) {
				for (int i = 0; i < 20_000; i++) {
					writer.write(text);
					writer.write('\n');
				}
			}
			System.out.println("JVM=" + System.getProperty("java.runtime.version") + " vendor="
					+ System.getProperty("java.vendor") + " maxHeap=" + Runtime.getRuntime().maxMemory());
			System.out.println("OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
					+ " arch=" + System.getProperty("os.arch") + " store=" + Files.getFileStore(path).type()
					+ " fileBytes=" + Files.size(path) + " visits=20 rows=20001 tabs=1");
			var model = new LogFileModel(path);
			long ownerId = Thread.currentThread().threadId();
			try (var recording = recording()) {
				for (int i = 0; i < 20; i++) {
					if (!text.equals(model.snapshot().readBatch(i * 1000).getLines().getFirst()))
						throw new AssertionError("Unexpected synchronous text");
				}
				recording.stop();
				recording.dump(synchronous);
			}
			var callbacks = new LinkedBlockingQueue<Runnable>();
			try (var loader = new LineLoadingService(callbacks::add); var recording = recording()) {
				var list = new VirtualLogList(model, loader);
				try {
					for (int i = 0; i < 20; i++) {
						int index = i * 1000;
						var line = list.get(index);
						while (line.isLoading()) {
							Runnable callback = callbacks.poll(10, TimeUnit.SECONDS);
							if (callback == null)
								throw new AssertionError("Loading did not finish");
							callback.run();
							line = list.get(index);
						}
						if (!line.isAvailable() || !text.equals(line.content()))
							throw new AssertionError("Unexpected asynchronous text");
					}
				} finally {
					list.close();
				}
				recording.stop();
				recording.dump(asynchronous);
			}
			long before = reads(synchronous, path, ownerId, true);
			long after = reads(asynchronous, path, ownerId, true);
			long workers = reads(asynchronous, path, ownerId, false);
			System.out.println("Former miss policy: ownerThreadFileReads=" + before);
			System.out.println("B05: ownerThreadFileReads=" + after + " workerFileReads=" + workers);
			System.out.println("JFR jdk.FileRead threshold=0; indexing excluded; warmed local storage; not a latency/heap benchmark");
			if (before == 0 || after != 0 || workers == 0)
				throw new AssertionError("Unexpected file-read placement");
		} finally {
			Files.deleteIfExists(path);
			Files.deleteIfExists(synchronous);
			Files.deleteIfExists(asynchronous);
		}
	}

	private static Recording recording() {
		Recording recording = new Recording();
		recording.enable("jdk.FileRead").withThreshold(Duration.ZERO);
		recording.start();
		return recording;
	}

	private static long reads(Path recording, Path fixture, long ownerId, boolean owner) throws Exception {
		return RecordingFile.readAllEvents(recording).stream()
				.filter(event -> event.getEventType().getName().equals("jdk.FileRead"))
				.filter(event -> fixture.toString().equals(event.getString("path")))
				.filter(event -> (event.getThread().getJavaThreadId() == ownerId) == owner).count();
	}
}