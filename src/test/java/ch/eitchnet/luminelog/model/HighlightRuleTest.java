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

import org.junit.jupiter.api.Test;

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
