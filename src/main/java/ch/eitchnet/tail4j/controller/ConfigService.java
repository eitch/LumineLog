package ch.eitchnet.tail4j.controller;

import ch.eitchnet.tail4j.model.Config;
import ch.eitchnet.tail4j.model.HighlightGroup;
import ch.eitchnet.tail4j.model.HighlightRule;
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

	private static final String APP_NAME = "tail4j";
	private static final String CONFIG_FILE_NAME = "config.json";

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
		List<HighlightRule> defaultRules = List.of(new HighlightRule("INFO", "#008000", false),
				new HighlightRule("WARN", "#ffa500", false), new HighlightRule("ERROR", "#ff0000", false),
				new HighlightRule("Exception", "#8b0000", false));
		HighlightGroup defaultGroup = new HighlightGroup("Default", List.of());
		HighlightGroup sl4jGroup = new HighlightGroup("Slf4j", defaultRules);
		return new Config(null, "Default", List.of(defaultGroup, sl4jGroup));
	}
}
