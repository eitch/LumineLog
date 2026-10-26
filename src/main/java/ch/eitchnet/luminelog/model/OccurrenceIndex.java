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

/** Immutable, ascending, zero-based original line identifiers, with explicit storage ownership. */
public interface OccurrenceIndex extends AutoCloseable {

	/** Returns the number of occurrences, or zero after close. */
	int size();

	/** Returns an original line identifier; throws if closed or the result index is out of bounds. */
	int lineIndex(int resultIndex);

	/** Returns the first result position whose line identifier is at least the argument, or size(). */
	int lowerBound(int originalLineIndex);

	/** Retained primitive array payload bytes, excluding JVM headers and references; zero after close. */
	long accountedBytes();

	/** Releases owned arrays. Idempotent; subsequent lookups throw IllegalStateException. */
	@Override
	void close();
}