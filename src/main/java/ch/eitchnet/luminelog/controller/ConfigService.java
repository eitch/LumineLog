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
package ch.eitchnet.luminelog.controller;

import ch.eitchnet.luminelog.model.Config;
import ch.eitchnet.luminelog.model.HighlightGroup;
import ch.eitchnet.luminelog.model.HighlightRule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ConfigService {

	private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);

	private static final String APP_NAME = "LumineLog";
	private static final String CONFIG_FILE_NAME = "config.json";
	public static final int DEFAULT_FONT_SIZE = getFontSize();

	private final Gson gson;
	private final Path configPath;

	public ConfigService() {
		this.gson = new GsonBuilder().setPrettyPrinting().create();
		this.configPath = getConfigPath();
	}

	public Config loadConfig() {
		if (Files.exists(configPath)) {
			try (FileReader reader = new FileReader(configPath.toFile())) {
				Config config = gson.fromJson(reader, Config.class);
				if (config != null) {
					if (config.getFontSize() == 0)
						config.setFontSize(DEFAULT_FONT_SIZE);
					return config;
				}
			} catch (IOException e) {
				logger.error("Failed to load config from {}, returning default", configPath, e);
			}
		}

		return createDefaultConfig();
	}

	public void saveConfig(Config config) {
		try {
			Files.createDirectories(configPath.getParent());
			try (FileWriter writer = new FileWriter(configPath.toFile())) {
				gson.toJson(config, writer);
			}
			logger.info("Saved config to {}", configPath);
		} catch (IOException e) {
			logger.error("Failed to save config to {}", configPath, e);
		}
	}

	private Path getConfigPath() {
		String userHome = System.getProperty("user.home");
		String os = System.getProperty("os.name").toLowerCase();

		Path path;
		if (os.contains("win")) {
			path = Paths.get(System.getenv("APPDATA"), APP_NAME, CONFIG_FILE_NAME);
		} else if (os.contains("mac")) {
			path = Paths.get(userHome, "Library", "Application Support", APP_NAME, CONFIG_FILE_NAME);
		} else {
			// Linux/Unix
			String configHome = System.getenv("XDG_CONFIG_HOME");
			if (configHome == null || configHome.isEmpty()) {
				path = Paths.get(userHome, ".config", APP_NAME, CONFIG_FILE_NAME);
			} else {
				path = Paths.get(configHome, APP_NAME, CONFIG_FILE_NAME);
			}
		}
		return path;
	}

	Config createDefaultConfig() {
		List<HighlightRule> defaultRules = List.of(new HighlightRule("INFO", "#b3ccff", false),
				new HighlightRule("WARN", "#ff9980", false), new HighlightRule("ERROR", "#ff0000", false),
				new HighlightRule("Exception", "#ff00ff", false));
		HighlightGroup defaultGroup = new HighlightGroup("Default", List.of());
		HighlightGroup sl4jGroup = new HighlightGroup("Slf4j", defaultRules);
		return new Config(List.of(), "Default", DEFAULT_FONT_SIZE, List.of(defaultGroup, sl4jGroup));
	}

	private static int getFontSize() {
		return 12;
	}
}
