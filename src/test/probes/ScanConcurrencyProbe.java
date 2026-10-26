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

import ch.eitchnet.luminelog.service.ScanService;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in scheduling counter comparison; blocked work models a slow/noninterruptible scan. */
public final class ScanConcurrencyProbe {

	public static void main(String[] args) throws Exception {
		int requests = 100;
		System.out.println("JVM=" + System.getProperty("java.runtime.version") + " vendor="
				+ System.getProperty("java.vendor") + " maxHeap=" + Runtime.getRuntime().maxMemory());
		System.out.println("OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
				+ " arch=" + System.getProperty("os.arch") + " requests=" + requests
				+ " fixture=latch-blocked work; no file I/O or decoded results");
		CountDownLatch legacyStarted = new CountDownLatch(requests);
		CountDownLatch legacyRelease = new CountDownLatch(1);
		var legacy = new ArrayList<Thread>();
		try {
			for (int i = 0; i < requests; i++) {
				Thread thread = new Thread(() -> {
					legacyStarted.countDown();
					await(legacyRelease);
				}, "LegacyOccurrenceSearch");
				legacy.add(thread);
				thread.start();
			}
			if (!legacyStarted.await(10, TimeUnit.SECONDS))
				throw new AssertionError("Legacy workers did not start");
			System.out.println("legacy policy: active=" + legacy.stream().filter(Thread::isAlive).count()
					+ " queued=0 rejected=0");
		} finally {
			legacyRelease.countDown();
			for (Thread thread : legacy)
				thread.join();
		}
		CountDownLatch boundedStarted = new CountDownLatch(ScanService.DEFAULT_WORKERS);
		CountDownLatch boundedRelease = new CountDownLatch(1);
		AtomicInteger active = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		try (var service = new ScanService(Runnable::run)) {
			for (int i = 0; i < requests; i++) {
				service.submit(new Object(), job -> {
					active.incrementAndGet();
					boundedStarted.countDown();
					await(boundedRelease);
					active.decrementAndGet();
					return null;
				}, (_, event) -> {
					if (event.state() == ScanService.State.REJECTED)
						rejected.incrementAndGet();
				});
			}
			if (!boundedStarted.await(10, TimeUnit.SECONDS))
				throw new AssertionError("Bounded workers did not start");
			System.out.println("B04: active=" + active.get() + " queued=" + service.queuedJobs()
					+ " rejected=" + rejected.get());
			if (active.get() != 2 || service.queuedJobs() != 8 || rejected.get() != 90)
				throw new AssertionError("Unexpected scheduling bounds");
		} finally {
			boundedRelease.countDown();
		}
	}

	private static void await(CountDownLatch release) {
		boolean interrupted = false;
		while (release.getCount() != 0) {
			try {
				release.await();
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}
}