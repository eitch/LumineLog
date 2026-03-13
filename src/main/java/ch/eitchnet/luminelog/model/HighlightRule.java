package ch.eitchnet.luminelog.model;

import java.util.regex.Pattern;

public class HighlightRule {
	private String pattern;
	private transient Pattern compiledPattern;
	private String color;
	private boolean isRegex;

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

	public void setPattern(String pattern) {
		this.pattern = pattern;
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

	public void setIsRegex(boolean isRegex) {
		this.isRegex = isRegex;
		if (isRegex) {
			compilePattern();
		} else {
			this.compiledPattern = null;
		}
	}

	private void compilePattern() {
		try {
			this.compiledPattern = Pattern.compile(pattern);
		} catch (Exception e) {
			this.compiledPattern = null;
		}
	}

	public boolean matches(String line) {
		if (isRegex) {
			if (compiledPattern == null) {
				compilePattern();
			}
			if (compiledPattern == null)
				return false;
			return compiledPattern.matcher(line).find();
		} else {
			return line.contains(pattern);
		}
	}

	public int countOccurrences(String line) {
		if (line == null || line.isEmpty() || pattern == null || pattern.isEmpty()) {
			return 0;
		}
		if (isRegex) {
			if (compiledPattern == null) {
				compilePattern();
			}
			if (compiledPattern == null)
				return 0;
			int count = 0;
			var matcher = compiledPattern.matcher(line);
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
			if (compiledPattern == null) {
				compilePattern();
			}
			if (compiledPattern == null)
				return matches;
			var matcher = compiledPattern.matcher(line);
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
