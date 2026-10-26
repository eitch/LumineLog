package ch.eitchnet.luminelog.probes;/*
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
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in allocation counter, source-launched outside the application module. */
public final class BatchAllocationProbe {

	private static volatile LogFileModel.Batch retained;

	public static void main(String[] args) throws Exception {
		ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		if (!bean.isThreadAllocatedMemorySupported())
			throw new UnsupportedOperationException("Thread allocation counters are unavailable");
		bean.setThreadAllocatedMemoryEnabled(true);
		Path path = Files.createTempFile(Path.of("target"), "batch-allocation-", ".log");
		try {
			Files.writeString(path, "x".repeat(LogFileModel.MAX_BATCH_BYTES));
			LogFileModel.Snapshot snapshot = new LogFileModel(path).snapshot();
			System.out.println("JVM=" + System.getProperty("java.runtime.version")
					+ " vendor=" + System.getProperty("java.vendor")
					+ " maxHeap=" + Runtime.getRuntime().maxMemory());
			System.out.println("OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
					+ " arch=" + System.getProperty("os.arch") + " store=" + Files.getFileStore(path).type());
			for (int i = 0; i < 100; i++)
				retained = snapshot.readBatch(0);
			int reads = 100;
			long thread = Thread.currentThread().threadId();
			long before = bean.getThreadAllocatedBytes(thread);
			for (int i = 0; i < reads; i++)
				retained = snapshot.readBatch(0);
			long allocated = bean.getThreadAllocatedBytes(thread) - before;
			if (retained.totalBytesToRead() != LogFileModel.MAX_BATCH_BYTES
					|| retained.getLines().getFirst().length() != LogFileModel.MAX_BATCH_BYTES)
				throw new AssertionError("Unexpected batch contents");
			System.out.println("payloadBytes=" + retained.totalBytesToRead() + " warmupReads=100 measuredReads="
					+ reads + " allocatedBytes=" + allocated + " bytesPerRead=" + allocated / reads);
		} finally {
			retained = null;
			Files.delete(path);
		}
	}
}