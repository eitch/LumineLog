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

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HighlightRuleTest {

	@Test
	void matchingSignatureIsImmutableAndExcludesStyleAndCompilationState() {
		HighlightRule rule = new HighlightRule("ERROR", "red", false);
		var signature = rule.matchingSignature();
		HighlightRule restored = new Gson().fromJson(
				"{\"pattern\":\"ERROR\",\"color\":\"blue\",\"isRegex\":false}", HighlightRule.class);
		assertEquals(signature, restored.matchingSignature());
		rule.setColor("green");
		assertEquals(signature, rule.matchingSignature());
		rule.setPattern("WARN");
		assertEquals(new HighlightRule.MatchingSignature("ERROR", false), signature);
		assertFalse(signature.equals(rule.matchingSignature()));
		rule.setPattern("ERROR");
		rule.setIsRegex(true);
		assertFalse(signature.equals(rule.matchingSignature()));
		restored.setIsRegex(true);
		assertEquals(rule.matchingSignature(), restored.matchingSignature());
	}

	@Test
	void invalidRegexCompilesOnceAcrossAllMatchingOperations() {
		CountingRule rule = new CountingRule("[");
		assertInvalidAcrossOperations(rule);
		assertEquals(1, rule.compilations);
	}

	@Test
	void gsonInvalidRegexCompilesLazilyOnce() {
		CountingRule rule = new Gson().fromJson(
				"{\"pattern\":\"[\",\"color\":\"red\",\"isRegex\":true}", CountingRule.class);
		assertEquals(0, rule.compilations);
		assertInvalidAcrossOperations(rule);
		assertEquals(1, rule.compilations);
	}

	@Test
	void patternEditsRecompileValidAndInvalidExpressions() {
		CountingRule rule = new CountingRule("[");
		rule.setPattern("error");
		assertTrue(rule.matches("error error"));
		assertEquals(2, rule.countOccurrences("error error"));
		assertEquals(List.of(new HighlightRule.MatchRange(0, 5), new HighlightRule.MatchRange(6, 11)),
				rule.findMatches("error error"));
		assertEquals(2, rule.compilations);
		rule.setPattern("(");
		assertInvalidAcrossOperations(rule);
		assertEquals(3, rule.compilations);
		rule.setPattern("warn");
		assertFalse(rule.matches("error"));
		assertTrue(rule.matches("warning"));
		assertEquals(4, rule.compilations);
	}

	@Test
	void modeEditsDiscardOldCompilationAndLiteralEditsDoNotCompile() {
		CountingRule rule = new CountingRule("[");
		rule.setIsRegex(false);
		assertTrue(rule.matches("[ ["));
		assertEquals(2, rule.countOccurrences("[ ["));
		assertEquals(2, rule.findMatches("[ [").size());
		rule.setPattern("error");
		assertEquals(1, rule.compilations);
		rule.setIsRegex(true);
		assertTrue(rule.matches("error"));
		assertEquals(2, rule.compilations);
		rule.setIsRegex(false);
		rule.setPattern("(");
		rule.setIsRegex(true);
		assertInvalidAcrossOperations(rule);
		assertEquals(3, rule.compilations);
	}

	@Test
	void unchangedInputsAndColorEditsPreserveCompilationState() {
		for (String expression : List.of("[", "error")) {
			CountingRule rule = new CountingRule(expression);
			rule.setPattern(new String(expression));
			rule.setIsRegex(true);
			rule.setColor("blue");
			rule.matches("error");
			rule.countOccurrences("error");
			rule.findMatches("error");
			assertEquals(1, rule.compilations);
		}
	}

	@Test
	void gsonValidRuleCompilesLazilyAndDoesNotPersistCacheState() {
		Gson gson = new Gson();
		CountingRule rule = gson.fromJson(
				"{\"pattern\":\"e[a-z]r\",\"color\":\"red\",\"isRegex\":true}", CountingRule.class);
		assertEquals(0, rule.compilations);
		assertEquals(2, rule.countOccurrences("ear ebr"));
		assertEquals(2, rule.findMatches("ear ebr").size());
		assertTrue(rule.matches("ear"));
		assertEquals(1, rule.compilations);
		assertEquals(gson.toJson(new HighlightRule("e[a-z]r", "red", true)), gson.toJson(rule));
		CountingRule restored = gson.fromJson(gson.toJson(rule), CountingRule.class);
		assertEquals(0, restored.compilations);
		assertTrue(restored.matches("ear"));
		assertEquals(1, restored.compilations);
	}

	@Test
	void concurrentFirstUseOfGsonRuleCompilesOnce() throws Exception {
		CountingRule rule = new Gson().fromJson(
				"{\"pattern\":\"[\",\"isRegex\":true}", CountingRule.class);
		try (var executor = Executors.newFixedThreadPool(4)) {
			Callable<Void> operations = () -> {
				assertInvalidAcrossOperations(rule);
				return null;
			};
			for (var result : executor.invokeAll(List.of(operations, operations, operations, operations)))
				result.get();
		}
		assertEquals(1, rule.compilations);
	}

	private static void assertInvalidAcrossOperations(HighlightRule rule) {
		for (int i = 0; i < 1000; i++) {
			assertFalse(rule.matches("error error"));
			assertEquals(0, rule.countOccurrences("error error"));
			assertTrue(rule.findMatches("error error").isEmpty());
		}
	}

	private static class CountingRule extends HighlightRule {
		private transient int compilations;

		CountingRule(String pattern) {
			super(pattern, "red", true);
		}

		@Override
		Pattern compileRegex(String expression) {
			compilations++;
			return super.compileRegex(expression);
		}
	}

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
