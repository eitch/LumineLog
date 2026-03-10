package ch.eitchnet.tail4j.model;

import java.util.regex.Pattern;

public class HighlightRule {
	private String pattern;
	private String color;
	private boolean isRegex;

	public HighlightRule(String pattern, String color, boolean isRegex) {
		this.pattern = pattern;
		this.color = color;
		this.isRegex = isRegex;
	}

	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
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
	}

	public boolean matches(String line) {
		if (isRegex) {
			try {
				return Pattern.compile(pattern).matcher(line).find();
			} catch (Exception e) {
				return false;
			}
		} else {
			return line.contains(pattern);
		}
	}

	public int countOccurrences(String line) {
		if (line == null || line.isEmpty() || pattern == null || pattern.isEmpty()) {
			return 0;
		}
		if (isRegex) {
			try {
				int count = 0;
				var matcher = Pattern.compile(pattern).matcher(line);
				while (matcher.find()) {
					count++;
				}
				return count;
			} catch (Exception e) {
				return 0;
			}
		} else {
			int count = 0;
			int lastIndex = 0;
			while ((lastIndex = line.indexOf(pattern, lastIndex)) != -1) {
				count++;
				lastIndex += pattern.length();
			}
			return count;
		}
	}
}
