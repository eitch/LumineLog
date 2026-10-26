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

import java.util.Objects;
import java.util.regex.Pattern;

public class HighlightRule {
	private String pattern;
	private transient Pattern compiledPattern;
	private transient CompilationState compilationState = CompilationState.UNINITIALIZED;
	private String color;
	private boolean isRegex;

	private enum CompilationState {
		UNINITIALIZED, VALID, INVALID
	}

	public HighlightRule(String pattern, String color, boolean isRegex) {
		this.pattern = pattern;
		this.color = color;
		this.isRegex = isRegex;
		if (isRegex) {
			compilePattern();
		}
	}

	public String getPattern() {
		return pattern;
	}

	public record MatchingSignature(String pattern, boolean regex) {
	}

	public synchronized MatchingSignature matchingSignature() {
		return new MatchingSignature(pattern, isRegex);
	}

	public synchronized void setPattern(String pattern) {
		if (Objects.equals(this.pattern, pattern))
			return;
		this.pattern = pattern;
		this.compiledPattern = null;
		this.compilationState = CompilationState.UNINITIALIZED;
		if (isRegex) {
			compilePattern();
		}
	}

	public String getColor() {
		return color;
	}

	public void setColor(String color) {
		this.color = color;
	}

	public boolean isIsRegex() {
		return isRegex;
	}

	public synchronized void setIsRegex(boolean isRegex) {
		if (this.isRegex == isRegex)
			return;
		this.isRegex = isRegex;
		this.compiledPattern = null;
		this.compilationState = CompilationState.UNINITIALIZED;
		if (isRegex) {
			compilePattern();
		}
	}

	private synchronized Pattern compilePattern() {
		// Gson may leave transient fields null when it bypasses the constructor.
		if (compilationState == CompilationState.VALID || compilationState == CompilationState.INVALID)
			return compiledPattern;
		try {
			this.compiledPattern = compileRegex(pattern);
			this.compilationState = CompilationState.VALID;
		} catch (Exception e) {
			this.compiledPattern = null;
			this.compilationState = CompilationState.INVALID;
		}
		return compiledPattern;
	}

	Pattern compileRegex(String expression) {
		return Pattern.compile(expression);
	}

	public boolean matches(String line) {
		if (isRegex) {
			Pattern compiled = compilePattern();
			if (compiled == null)
				return false;
			return compiled.matcher(line).find();
		} else {
			return line.contains(pattern);
		}
	}

	public int countOccurrences(String line) {
		if (line == null || line.isEmpty() || pattern == null || pattern.isEmpty()) {
			return 0;
		}
		if (isRegex) {
			Pattern compiled = compilePattern();
			if (compiled == null)
				return 0;
			int count = 0;
			var matcher = compiled.matcher(line);
			while (matcher.find()) {
				count++;
			}
			return count;
		} else {
			int count = 0;
			int lastIndex = 0;
			int patternLen = pattern.length();
			while ((lastIndex = line.indexOf(pattern, lastIndex)) != -1) {
				count++;
				lastIndex += patternLen;
			}
			return count;
		}
	}

	public java.util.List<MatchRange> findMatches(String line) {
		java.util.List<MatchRange> matches = new java.util.ArrayList<>();
		if (line == null || line.isEmpty() || pattern == null || pattern.isEmpty()) {
			return matches;
		}
		if (isRegex) {
			Pattern compiled = compilePattern();
			if (compiled == null)
				return matches;
			var matcher = compiled.matcher(line);
			while (matcher.find()) {
				matches.add(new MatchRange(matcher.start(), matcher.end()));
			}
		} else {
			int lastIndex = 0;
			int patternLen = pattern.length();
			while ((lastIndex = line.indexOf(pattern, lastIndex)) != -1) {
				matches.add(new MatchRange(lastIndex, lastIndex + patternLen));
				lastIndex += patternLen;
			}
		}
		return matches;
	}

	public record MatchRange(int start, int end) {
	}
}
