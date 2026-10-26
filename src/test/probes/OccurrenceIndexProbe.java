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
import ch.eitchnet.luminelog.model.LogLine;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/** Opt-in classpath probe, deliberately outside modular test sources. */
public final class OccurrenceIndexProbe {

	private static volatile Object retained;

	public static void main(String[] args) {
		int count = args.length == 0 ? 1_000_000 : Integer.parseInt(args[0]);
		if (count < 0)
			throw new IllegalArgumentException("Count must be nonnegative");
		java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
		if (!(platformBean instanceof com.sun.management.ThreadMXBean bean)
				|| !bean.isThreadAllocatedMemorySupported())
			throw new IllegalStateException("Thread allocation measurement is unavailable on this JVM");
		bean.setThreadAllocatedMemoryEnabled(true);
		for (int i = 0; i < 3; i++) {
			retained = formerList(10_000);
			try (ChunkedOccurrenceIndex index = primitiveIndex(10_000)) {
				retained = index;
			}
		}
		long thread = Thread.currentThread().threadId();
		long before = bean.getThreadAllocatedBytes(thread);
		List<LogLine> former = formerList(count);
		long listAllocated = bean.getThreadAllocatedBytes(thread) - before;
		retained = former;
		before = bean.getThreadAllocatedBytes(thread);
		try (ChunkedOccurrenceIndex index = primitiveIndex(count)) {
			long indexAllocated = bean.getThreadAllocatedBytes(thread) - before;
			long retainedCharacters = 0;
			if (former.size() != index.size())
				throw new AssertionError("Result count differs");
			for (int i = 0; i < count; i++) {
				if (former.get(i).lineNumber() - 1 != index.lineIndex(i)
						|| !former.get(i).content().equals(text(i)))
					throw new AssertionError("Dataset differs at " + i);
				retainedCharacters += former.get(i).content().length();
			}
			System.out.println("JVM=" + System.getProperty("java.runtime.version") + " vendor="
					+ System.getProperty("java.vendor") + " maxHeap=" + Runtime.getRuntime().maxMemory()
					+ " OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
					+ " arch=" + System.getProperty("os.arch") + " matches=" + count);
			System.out.println("Same generated dataset: every line matches ERROR; zero-based identifiers 0..count-1");
			System.out.println("Retained source-text characters: formerList=" + retainedCharacters + " primitiveIndex=0");
			System.out.println("ThreadMXBean allocated bytes (not retained heap): formerList=" + listAllocated
					+ " primitiveIndex=" + indexAllocated);
			System.out.println("Index accounted retained primitive payload bytes (excludes headers/references)="
					+ index.accountedBytes());
			index.close();
			System.out.println("Index accounted bytes after close=" + index.accountedBytes());
		} finally {
			retained = null;
		}
	}

	private static List<LogLine> formerList(int count) {
		List<LogLine> matches = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			String text = text(i);
			if (text.contains("ERROR"))
				matches.add(new LogLine(i + 1, text));
		}
		return matches;
	}

	private static ChunkedOccurrenceIndex primitiveIndex(int count) {
		ChunkedOccurrenceIndex.Builder builder = new ChunkedOccurrenceIndex.Builder();
		for (int i = 0; i < count; i++) {
			String text = text(i);
			if (text.contains("ERROR"))
				builder.add(i);
		}
		return builder.build();
	}

	private static String text(int line) {
		return "2026-10-26 ERROR generated matching log line " + line + " payload=" + "x".repeat(128);
	}
}