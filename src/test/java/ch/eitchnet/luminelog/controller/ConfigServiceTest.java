package ch.eitchnet.luminelog.controller;

import ch.eitchnet.luminelog.model.Config;
import ch.eitchnet.luminelog.model.HighlightGroup;
import ch.eitchnet.luminelog.model.HighlightRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigServiceTest {

	@Test
	public void testCreateDefaultConfig() {
		ConfigService configService = new ConfigService() {
			@Override
			public Config loadConfig() {
				// Force return default config by bypassing file check
				return createDefaultConfig();
			}

			@Override
			public ch.eitchnet.luminelog.model.Config createDefaultConfig() {
				// Make it accessible for test
				return super.createDefaultConfig();
			}
		};
		Config config = configService.loadConfig();

		assertNotNull(config);
		assertEquals("Default", config.getLastGroup());
		List<HighlightGroup> groups = config.getHighlightGroups();
		assertEquals(2, groups.size());

		HighlightGroup defaultGroup = groups.getFirst();
		assertEquals("Default", defaultGroup.getName());

		List<HighlightRule> rules = defaultGroup.getRules();
		assertEquals(0, rules.size());

		HighlightGroup slf4jGroup = groups.get(1);
		assertEquals("Slf4j", slf4jGroup.getName());
		rules = slf4jGroup.getRules();
		assertEquals(4, rules.size());

		assertEquals("INFO", rules.getFirst().getPattern());
		assertEquals("#b3ccff", rules.get(0).getColor());
		assertFalse(rules.get(0).isIsRegex());

		assertEquals("WARN", rules.get(1).getPattern());
		assertEquals("#ff9980", rules.get(1).getColor());
		assertFalse(rules.get(1).isIsRegex());

		assertEquals("ERROR", rules.get(2).getPattern());
		assertEquals("#ff0000", rules.get(2).getColor());
		assertFalse(rules.get(2).isIsRegex());

		assertEquals("Exception", rules.get(3).getPattern());
		assertEquals("#ff00ff", rules.get(3).getColor());
		assertFalse(rules.get(3).isIsRegex());
	}
}
