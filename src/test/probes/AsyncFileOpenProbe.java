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
import ch.eitchnet.luminelog.service.FileOpenService;
import ch.eitchnet.luminelog.service.ScanService;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Compares initial-indexing read placement, not disk throughput or UI latency. */
public final class AsyncFileOpenProbe {

	public static void main(String[] args) throws Exception {
		Path path = Files.createTempFile(Path.of("target"), "file-opening-", ".log").toAbsolutePath();
		Path synchronous = Files.createTempFile(Path.of("target"), "sync-opening-", ".jfr");
		Path asynchronous = Files.createTempFile(Path.of("target"), "async-opening-", ".jfr");
		try {
			try (var output = new RandomAccessFile(path.toFile(), "rw")) {
				output.setLength(64L * 1024 * 1024);
			}
			System.out.println("JVM=" + System.getProperty("java.runtime.version") + " vendor="
					+ System.getProperty("java.vendor") + " maxHeap=" + Runtime.getRuntime().maxMemory());
			System.out.println("OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
					+ " arch=" + System.getProperty("os.arch") + " store=" + Files.getFileStore(path).type()
					+ " fileBytes=" + Files.size(path) + " sparse=true rows=1 opens=1 per policy");
			long ownerId = Thread.currentThread().threadId();
			try (var recording = recording()) {
				if (new LogFileModel(path).getLineCount() != 1)
					throw new AssertionError("Unexpected synchronous index");
				recording.stop();
				recording.dump(synchronous);
			}
			var callbacks = new LinkedBlockingQueue<Runnable>();
			try (var opener = new FileOpenService(callbacks::add); var recording = recording()) {
				boolean[] finished = { false };
				opener.open(new Object(), path, (_, event) -> {
					if (event.state() == ScanService.State.SUCCEEDED) {
						if (event.result().getLineCount() != 1)
							throw new AssertionError("Unexpected asynchronous index");
						finished[0] = true;
					} else if (event.state() == ScanService.State.FAILED || event.state() == ScanService.State.REJECTED) {
						throw new AssertionError("Opening failed", event.error());
					}
				});
				while (!finished[0]) {
					Runnable callback = callbacks.poll(10, TimeUnit.SECONDS);
					if (callback == null)
						throw new AssertionError("Opening did not finish");
					callback.run();
				}
				recording.stop();
				recording.dump(asynchronous);
			}
			long before = reads(synchronous, path, ownerId, true);
			long after = reads(asynchronous, path, ownerId, true);
			long workers = reads(asynchronous, path, ownerId, false);
			System.out.println("Former opening policy: ownerThreadFileReads=" + before);
			System.out.println("B06: ownerThreadFileReads=" + after + " workerFileReads=" + workers);
			System.out.println("JFR jdk.FileRead threshold=0; initial indexing only; sparse local fixture; not a latency/heap benchmark");
			if (before == 0 || after != 0 || workers == 0)
				throw new AssertionError("Unexpected initial-indexing read placement");
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