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
}
