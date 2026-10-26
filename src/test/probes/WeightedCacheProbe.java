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
import ch.eitchnet.luminelog.model.LogLine;
import ch.eitchnet.luminelog.model.VirtualLogList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Opt-in retained-data accounting comparison; not a JVM heap measurement. */
public final class WeightedCacheProbe {

	public static void main(String[] args) throws Exception {
		Path path = Files.createTempFile(Path.of("target"), "weighted-cache-", ".log");
		try {
			try (var writer = Files.newBufferedWriter(path)) {
				String longLine = "x".repeat(512 * 1024 - 1) + "\n";
				for (int i = 0; i < 24; i++) {
					writer.write(longLine);
					writer.write("short\n");
				}
			}
			System.out.println("JVM=" + System.getProperty("java.runtime.version") + " vendor="
					+ System.getProperty("java.vendor") + " maxHeap=" + Runtime.getRuntime().maxMemory());
			System.out.println("OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
					+ " arch=" + System.getProperty("os.arch") + " store=" + Files.getFileStore(path).type()
					+ " fileBytes=" + Files.size(path));
			var model = new LogFileModel(path);
			var first = new VirtualLogList(model);
			var second = new VirtualLogList(model);
			long peak = 0;
			for (int i = 0; i < first.size(); i++) {
				if (first.get(i).isReadError() || second.get(i).isReadError())
					throw new AssertionError("Unexpected read failure");
				peak = Math.max(peak, weight(first) + weight(second));
			}
			System.out.println("tabs=2 rows=" + first.size() + " perTab=" + weight(first)
					+ " combined=" + (weight(first) + weight(second)) + " peakCombined=" + peak);
			System.out.println("Shared model: one current snapshot/index, logicalOffsetBytes="
					+ 8L * first.size() + "; no scans/results/pending tasks; synchronous read temporaries excluded");
		} finally {
			Files.delete(path);
		}
	}

	private static long stringWeight(String value) {
		return 64 + ((2L * value.length() + 7) & ~7L);
	}

	private static long weight(VirtualLogList list) throws Exception {
		var cacheField = VirtualLogList.class.getDeclaredField("cache");
		cacheField.setAccessible(true);
		@SuppressWarnings("unchecked")
		var cache = (Map<Integer, LogLine>) cacheField.get(list);
		long weight = 0;
		for (LogLine line : cache.values())
			weight += 192 + stringWeight(line.content())
					+ (line.errorMessage() == null ? 0 : stringWeight(line.errorMessage()));
		var batchField = VirtualLogList.class.getDeclaredField("currentBatch");
		batchField.setAccessible(true);
		var batch = (LogFileModel.Batch) batchField.get(list);
		if (batch != null) {
			weight += 128 + ((batch.totalBytesToRead() + 7) & ~7L) + 8L * batch.getLines().size();
			for (String line : batch.getLines())
				weight += stringWeight(line);
		}
		return weight;
	}
}