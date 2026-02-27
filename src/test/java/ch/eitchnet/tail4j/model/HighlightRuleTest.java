package ch.eitchnet.tail4j.model;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class HighlightRuleTest {

    @Test
    public void testCountOccurrences() {
        HighlightRule rule = new HighlightRule("error", "red", false);
        assertEquals(1, rule.countOccurrences("This is an error."));
        assertEquals(2, rule.countOccurrences("error error"));
        assertEquals(0, rule.countOccurrences("No match here."));
        assertEquals(0, rule.countOccurrences(null));
        assertEquals(0, rule.countOccurrences(""));
    }

    @Test
    public void testCountOccurrencesRegex() {
        HighlightRule rule = new HighlightRule("e[a-z]r", "red", true);
        assertEquals(1, rule.countOccurrences("ear"));
        assertEquals(2, rule.countOccurrences("ear ebr"));
        assertEquals(0, rule.countOccurrences("er")); // regex is e[a-z]r, so needs a letter in middle
        assertEquals(0, rule.countOccurrences(null));
    }

    @Test
    public void testCountOccurrencesOverlapping() {
        HighlightRule rule = new HighlightRule("aa", "red", false);
        // "aaa" contains "aa" at index 0 and 1.
        // My implementation: count++; lastIndex += pattern.length();
        // lastIndex starts at 0. line.indexOf("aa", 0) -> 0. count=1. lastIndex=2.
        // line.indexOf("aa", 2) -> -1.
        // So "aaa" counts as 1. This is standard for non-overlapping.
        assertEquals(1, rule.countOccurrences("aaa"));
        assertEquals(2, rule.countOccurrences("aaaa"));
    }
}
