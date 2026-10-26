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

import ch.eitchnet.luminelog.model.ChunkedOccurrenceIndex;
import ch.eitchnet.luminelog.model.SpillableOccurrenceIndex;

import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in classpath probe: run each mode in its own JVM with -Xmx32m. */
public final class SpillableOccurrenceIndexProbe {

	public static void main(String[] args) throws Exception {
		String mode = args.length == 0 ? "spill" : args[0];
		int count = args.length < 2 ? 10_000_000 : Integer.parseInt(args[1]);
		if (count < 0 || !(mode.equals("spill") || mode.equals("chunked")))
			throw new IllegalArgumentException("Usage: SpillableOccurrenceIndexProbe [spill|chunked] [count]");
		System.out.println("JVM=" + System.getProperty("java.runtime.version") + " vendor="
				+ System.getProperty("java.vendor") + " maxHeap=" + Runtime.getRuntime().maxMemory()
				+ " OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
				+ " arch=" + System.getProperty("os.arch") + " mode=" + mode + " matches=" + count);
		System.out.println("Dataset: generated ascending zero-based identifiers 0..count-1; no source text retained");
		long started = System.nanoTime();
		if (mode.equals("chunked")) {
			ChunkedOccurrenceIndex.Builder builder = new ChunkedOccurrenceIndex.Builder();
			for (int i = 0; i < count; i++)
				builder.add(i);
			try (ChunkedOccurrenceIndex index = builder.build()) {
				if (index.size() != count)
					throw new AssertionError("Wrong count");
				for (int i = 0; i < count; i++) {
					if (index.lineIndex(i) != i)
						throw new AssertionError("Wrong identifier at " + i);
				}
				System.out.println("accountedBytes=" + index.accountedBytes());
			}
		} else {
			Path data;
			try (SpillableOccurrenceIndex.Builder builder = new SpillableOccurrenceIndex.Builder()) {
				for (int i = 0; i < count; i++)
					builder.add(i);
				try (SpillableOccurrenceIndex index = builder.build()) {
					if (index.size() != count)
						throw new AssertionError("Wrong count");
					int visited = 0;
					for (int page = 0; visited < count; page++) {
						for (int value : index.readPage(page)) {
							if (value != visited++)
								throw new AssertionError("Wrong identifier at " + (visited - 1));
						}
					}
					for (int i = 0; i < count; i += 1 + count / 100) {
						if (index.lineIndex(i) != i || index.lowerBound(i) != i)
							throw new AssertionError("Wrong random lookup at " + i);
					}
					if (index.lowerBound(Integer.MAX_VALUE) != count)
						throw new AssertionError("Wrong upper bound");
					data = index.storagePath();
					System.out.println("verifiedIdentifiers=" + visited + " spilled=" + index.isSpilled()
							+ " accountedBytes=" + index.accountedBytes() + " diskBytes=" + (data == null ? 0 : Files.size(data))
							+ " thresholdIdentifiers=" + SpillableOccurrenceIndex.DEFAULT_THRESHOLD_IDENTIFIERS
							+ " pageIdentifiers=" + SpillableOccurrenceIndex.PAGE_SIZE);
				}
			}
			if (data != null && Files.exists(data.getParent()))
				throw new AssertionError("Owned directory was not deleted: " + data.getParent());
			System.out.println("Owned spill directory deleted after close");
		}
		System.out.println("elapsedMillis=" + (System.nanoTime() - started) / 1_000_000);
	}
}