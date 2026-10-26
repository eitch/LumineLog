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

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** Immutable bounded-memory storage. Synchronous disk lookups are for background copy work only. */
public final class SpillableOccurrenceIndex implements OccurrenceIndex {

	public static final int DEFAULT_THRESHOLD_IDENTIFIERS = 65_536;
	public static final int PAGE_SIZE = 4096;
	public static final Path DEFAULT_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "luminelog-occurrences");
	private static final String MARKER = "owner";
	private static final String LOCK = "owner.lock";
	private static final String DATA = "occurrences.bin";
	private static final String OWNER = "LumineLog occurrence storage v1\n";
	private static final Set<String> OWNED_FILES = Set.of(MARKER, LOCK, DATA);
	private static final IOHooks NO_HOOKS = new IOHooks() {};

	private int[] identifiers;
	private OwnedStorage storage;
	private final Path path;
	private final IOHooks hooks;
	private final int count;
	private final boolean spilled;
	private boolean closed;

	private SpillableOccurrenceIndex(int[] identifiers, OwnedStorage storage, int count) {
		this.identifiers = identifiers;
		this.storage = storage;
		this.path = storage == null ? null : storage.directory.resolve(DATA);
		this.hooks = storage == null ? NO_HOOKS : storage.hooks;
		this.count = count;
		this.spilled = storage != null;
	}

	@Override
	public synchronized int size() {
		return closed ? 0 : count;
	}

	public boolean isSpilled() {
		return spilled;
	}

	/** The owned data file, or null for memory mode; retained for diagnostics after close. */
	public Path storagePath() {
		return path;
	}

	@Override
	public synchronized long accountedBytes() {
		return identifiers == null ? 0 : (long) identifiers.length * Integer.BYTES;
	}

	/** Returns a fresh caller-owned snapshot; modifying it never changes the index. */
	public int[] readPage(int pageNumber) throws IOException {
		long start = (long) pageNumber * PAGE_SIZE;
		int length;
		synchronized (this) {
			ensureOpen();
			if (pageNumber < 0 || start >= count)
				throw new IndexOutOfBoundsException("Page outside occurrence index: " + pageNumber);
			length = (int) Math.min(PAGE_SIZE, count - start);
			if (!spilled)
				return Arrays.copyOfRange(identifiers, (int) start, (int) start + length);
		}
		ByteBuffer buffer = ByteBuffer.allocate(length * Integer.BYTES);
		readFully(buffer, start * Integer.BYTES);
		buffer.flip();
		int[] page = new int[length];
		buffer.asIntBuffer().get(page);
		return page;
	}

	@Override
	public int lineIndex(int resultIndex) {
		synchronized (this) {
			ensureOpen();
			Objects.checkIndex(resultIndex, count);
			if (!spilled)
				return identifiers[resultIndex];
		}
		return readIdentifier(resultIndex);
	}

	private int readIdentifier(int resultIndex) {
		try {
			ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
			readFully(buffer, (long) resultIndex * Integer.BYTES);
			return buffer.flip().getInt();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public int lowerBound(int originalLineIndex) {
		int[] memory;
		synchronized (this) {
			ensureOpen();
			memory = identifiers;
		}
		int low = 0;
		int high = count;
		while (low < high) {
			int middle = low + ((high - low) >>> 1);
			if ((memory == null ? readIdentifier(middle) : memory[middle]) < originalLineIndex)
				low = middle + 1;
			else
				high = middle;
		}
		return low;
	}

	private void readFully(ByteBuffer buffer, long position) throws IOException {
		// Independent handles keep blocking IO outside the lifecycle monitor, with no shared seek state.
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			hooks.beforeRead();
			while (buffer.hasRemaining()) {
				int read = channel.read(buffer, position);
				if (read < 0)
					throw new EOFException("Truncated occurrence storage: " + path);
				if (read == 0)
					throw new IOException("No progress reading occurrence storage: " + path);
				position += read;
			}
		}
	}

	@Override
	public synchronized void close() {
		closed = true;
		identifiers = null;
		if (storage != null) {
			try {
				storage.close();
				storage = null;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	private void ensureOpen() {
		if (closed)
			throw new IllegalStateException("Occurrence index is closed");
	}

	/** Single-use, non-thread-safe builder. Any failed add/build aborts the builder. */
	public static final class Builder implements AutoCloseable {

		private final Path directory;
		private final int thresholdIdentifiers;
		private final IOHooks hooks;
		private int[] identifiers;
		private ByteBuffer buffer;
		private OwnedStorage storage;
		private int size;
		private int last = -1;
		private boolean finished;

		public Builder() {
			this(DEFAULT_ROOT, DEFAULT_THRESHOLD_IDENTIFIERS);
		}

		/** directory is the root beneath which private per-result directories are created. */
		public Builder(Path directory, int thresholdIdentifiers) {
			this(directory, thresholdIdentifiers, NO_HOOKS);
		}

		Builder(Path directory, int thresholdIdentifiers, IOHooks hooks) {
			if (thresholdIdentifiers < 0)
				throw new IllegalArgumentException("Threshold must be nonnegative");
			this.directory = Objects.requireNonNull(directory);
			this.thresholdIdentifiers = thresholdIdentifiers;
			this.hooks = Objects.requireNonNull(hooks);
			this.identifiers = new int[Math.min(thresholdIdentifiers, PAGE_SIZE)];
		}

		public void add(int originalLineIndex) throws IOException {
			ensureActive();
			try {
				if (originalLineIndex < 0 || originalLineIndex <= last)
					throw new IllegalArgumentException("Line identifiers must be nonnegative and strictly ascending");
				if (size == Integer.MAX_VALUE)
					throw new IllegalStateException("Occurrence index capacity exceeded");
				if (storage == null && size == thresholdIdentifiers)
					spill();
				if (storage == null) {
					if (size == identifiers.length)
						identifiers = Arrays.copyOf(identifiers,
								(int) Math.min(thresholdIdentifiers, Math.max(1L, (long) identifiers.length * 2)));
					identifiers[size] = originalLineIndex;
				} else {
					if (buffer.remaining() < Integer.BYTES)
						flush();
					buffer.putInt(originalLineIndex);
				}
				size++;
				last = originalLineIndex;
			} catch (IOException | RuntimeException e) {
				abort(e);
				throw e;
			}
		}

		private void spill() throws IOException {
			storage = OwnedStorage.create(directory, hooks);
			buffer = ByteBuffer.allocate(PAGE_SIZE * Integer.BYTES);
			for (int i = 0; i < size; i++) {
				if (buffer.remaining() < Integer.BYTES)
					flush();
				buffer.putInt(identifiers[i]);
			}
			identifiers = null;
		}

		private void flush() throws IOException {
			buffer.flip();
			hooks.beforeWrite();
			while (buffer.hasRemaining()) {
				if (storage.data.write(buffer) <= 0)
					throw new IOException("No progress writing occurrence storage");
			}
			buffer.clear();
		}

		public SpillableOccurrenceIndex build() throws IOException {
			ensureActive();
			try {
				if (storage != null) {
					flush();
					hooks.beforeBuild();
					if (storage.data.size() != (long) size * Integer.BYTES)
						throw new IOException("Unexpected occurrence storage length");
					storage.data.close();
					storage.data = null;
				}
				int[] owned = storage == null ? Arrays.copyOf(identifiers, size) : null;
				SpillableOccurrenceIndex index = new SpillableOccurrenceIndex(owned, storage, size);
				storage = null;
				identifiers = null;
				buffer = null;
				finished = true;
				return index;
			} catch (IOException | RuntimeException e) {
				abort(e);
				throw e;
			}
		}

		private void ensureActive() {
			if (finished)
				throw new IllegalStateException("Builder is closed or has transferred ownership");
		}

		private void abort(Exception cause) {
			try {
				close();
			} catch (IOException cleanup) {
				cause.addSuppressed(cleanup);
			}
		}

		@Override
		public void close() throws IOException {
			finished = true;
			identifiers = null;
			buffer = null;
			if (storage != null) {
				storage.close();
				storage = null;
			}
		}
	}

	/** Test seams fail real storage operations without replacing their implementations. */
	interface IOHooks {
		default void beforeWrite() throws IOException {}
		default void beforeRead() throws IOException {}
		default void beforeBuild() throws IOException {}
	}

	/** Deletes only recognized, unlocked, non-symlink result directories directly beneath root. */
	public static void cleanupAbandoned(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
			return;
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Occurrence root is not a non-symlink directory: " + root);
		try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
			for (Path directory : directories) {
				try {
					cleanupDirectory(directory);
				} catch (java.nio.file.NoSuchFileException e) {
					// Another owner may finish deleting at any point during discovery.
				}
			}
		}
	}

	private static void cleanupDirectory(Path directory) throws IOException {
		if (!recognized(directory))
			return;
		try (FileChannel channel = FileChannel.open(directory.resolve(LOCK), StandardOpenOption.WRITE,
				LinkOption.NOFOLLOW_LINKS)) {
			FileLock lock;
			try {
				lock = channel.tryLock();
			} catch (OverlappingFileLockException e) {
				return;
			}
			if (lock == null)
				return;
			try (lock) {
				if (!recognized(directory))
					return;
				Files.deleteIfExists(directory.resolve(DATA));
				Files.delete(directory.resolve(MARKER));
			}
		}
		Files.deleteIfExists(directory.resolve(LOCK));
		Files.delete(directory);
	}

	private static boolean recognized(Path directory) throws IOException {
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
			return false;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) {
				if (!OWNED_FILES.contains(entry.getFileName().toString())
						|| !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
					return false;
			}
		}
		Path marker = directory.resolve(MARKER);
		if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isRegularFile(directory.resolve(LOCK), LinkOption.NOFOLLOW_LINKS))
			return false;
		try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			byte[] expected = OWNER.getBytes(StandardCharsets.UTF_8);
			if (channel.size() != expected.length)
				return false;
			ByteBuffer content = ByteBuffer.allocate(expected.length);
			while (content.hasRemaining()) {
				if (channel.read(content) <= 0)
					return false;
			}
			return Arrays.equals(expected, content.array());
		}
	}

	private static final class OwnedStorage implements AutoCloseable {
		private final Path directory;
		private final IOHooks hooks;
		private FileChannel lockChannel;
		private FileLock lock;
		private FileChannel data;

		private OwnedStorage(Path directory, IOHooks hooks) {
			this.directory = directory;
			this.hooks = hooks;
		}

		private static OwnedStorage create(Path root, IOHooks hooks) throws IOException {
			Files.createDirectories(root);
			if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Occurrence root is not a non-symlink directory: " + root);
			cleanupAbandoned(root);
			Path directory;
			if (Files.getFileStore(root).supportsFileAttributeView("posix"))
				directory = Files.createTempDirectory(root, "result-",
						PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			else
				directory = Files.createTempDirectory(root, "result-");
			OwnedStorage storage = new OwnedStorage(directory, hooks);
			try {
				storage.lockChannel = FileChannel.open(directory.resolve(LOCK), StandardOpenOption.CREATE_NEW,
						StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
				storage.lock = storage.lockChannel.lock();
				Files.writeString(directory.resolve(MARKER), OWNER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
				storage.data = FileChannel.open(directory.resolve(DATA), StandardOpenOption.CREATE_NEW,
						StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
				return storage;
			} catch (IOException | RuntimeException e) {
				try {
					storage.close();
				} catch (IOException cleanup) {
					e.addSuppressed(cleanup);
				}
				throw e;
			}
		}

		@Override
		public void close() throws IOException {
			IOException failure = null;
			if (data != null) {
				try {
					data.close();
				} catch (IOException e) {
					failure = e;
				}
			}
			try {
				Files.deleteIfExists(directory.resolve(DATA));
				Files.deleteIfExists(directory.resolve(MARKER));
			} catch (IOException e) {
				failure = append(failure, e);
			}
			try {
				if (lock != null && lock.isValid())
					lock.release();
			} catch (IOException e) {
				failure = append(failure, e);
			}
			try {
				if (lockChannel != null)
					lockChannel.close();
			} catch (IOException e) {
				failure = append(failure, e);
			}
			try {
				Files.deleteIfExists(directory.resolve(LOCK));
				Files.deleteIfExists(directory);
			} catch (IOException e) {
				failure = append(failure, e);
			}
			if (failure != null)
				throw failure;
		}

		private static IOException append(IOException failure, IOException next) {
			if (failure == null)
				return next;
			failure.addSuppressed(next);
			return failure;
		}
	}
}