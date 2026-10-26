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
package ch.eitchnet.luminelog.service;

import ch.eitchnet.luminelog.model.LogFileModel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** File indexing has its own bounded workers, independent of content scans. */
public final class FileOpenService implements AutoCloseable {

	public static final int DEFAULT_WORKERS = 2;
	public static final int DEFAULT_QUEUE_CAPACITY = 8;

	private final ScanService service;
	private final Opener opener;

	public FileOpenService(Executor callbacks) {
		this(DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY, callbacks);
	}

	public FileOpenService(int workers, int queueCapacity, Executor callbacks) {
		this(workers, queueCapacity, callbacks, LogFileModel::new);
	}

	FileOpenService(int workers, int queueCapacity, Executor callbacks, Opener opener) {
		this.opener = Objects.requireNonNull(opener, "opener");
		this.service = new ScanService(workers, queueCapacity, callbacks);
	}

	public ScanService.Job<LogFileModel> open(Object owner, Path path,
			ScanService.Listener<LogFileModel> listener) {
		Objects.requireNonNull(path, "path");
		return service.submit(owner, job -> opener.open(path, job::isCancelled), listener);
	}

	public void cancel(Object owner) {
		service.cancel(owner);
	}

	public int activeJobs() {
		return service.activeJobs();
	}

	public int queuedJobs() {
		return service.queuedJobs();
	}

	@Override
	public void close() {
		service.close();
	}

	@FunctionalInterface
	interface Opener {
		LogFileModel open(Path path, BooleanSupplier cancelled) throws IOException;
	}
}