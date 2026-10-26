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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone storage allocation probe; run with -Xmx32m and an expected outcome argument. */
public final class LogFileModelHeapProbe {

	public static void main(String[] args) throws IOException {
		Path path = Path.of(args[0]);
		try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
			file.setLength(0);
			file.setLength(128L * 1024 * 1024);
		}
		System.out.println("JVM=" + System.getProperty("java.runtime.version")
				+ " vendor=" + System.getProperty("java.vendor")
				+ " maxHeap=" + Runtime.getRuntime().maxMemory());
		System.out.println("OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
				+ " arch=" + System.getProperty("os.arch"));
		System.out.println("file=" + path.toAbsolutePath() + " bytes=" + Files.size(path)
				+ " store=" + Files.getFileStore(path).type() + " sparseZeroFilled=true newlineFree=true");
		LogFileModel model = new LogFileModel(path);
		System.out.println("indexedLines=" + model.getLineCount() + " indexedBytes=" + model.snapshot().fileSize());
		String outcome;
		try {
			LogFileModel.Batch batch = model.snapshot().readBatch(0);
			System.out.println("decodedChars=" + batch.getLines().getFirst().length());
			outcome = "ACCEPTED";
		} catch (IOException e) {
			System.out.println(e.getClass().getName() + ": " + e.getMessage());
			outcome = "IO_EXCEPTION";
		} catch (OutOfMemoryError e) {
			System.out.println("OutOfMemoryError: " + e.getMessage());
			outcome = "OUT_OF_MEMORY";
		}
		System.out.println("allocationOutcome=" + outcome);
		if (!outcome.equals(args[1]))
			throw new AssertionError("Expected " + args[1] + " but got " + outcome);
	}
}