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

public record LogLine(int lineNumber, String content, String errorMessage) {

	public LogLine(int lineNumber, String content) {
		this(lineNumber, content, null);
	}

	public static LogLine readError(int lineNumber, String message) {
		return new LogLine(lineNumber, "", java.util.Objects.requireNonNull(message));
	}

	public boolean isReadError() {
		return errorMessage != null;
	}
}
