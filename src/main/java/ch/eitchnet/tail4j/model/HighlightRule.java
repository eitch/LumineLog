package ch.eitchnet.tail4j.model;

import java.util.regex.Pattern;

public record HighlightRule(String pattern, String color, boolean isRegex) {
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
