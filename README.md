# ![LumineLog Logo](src/main/resources/ch/eitchnet/luminelog/assets/logo.svg) Tail4j Log Viewer

Tail4j (also known as LumineLog) is a modern, cross-platform log viewer application built with **JavaFX**. It provides a real-time "tail -f" experience with powerful highlighting and multi-file support.

## Features

- **Real-time Tailing**: Automatically follows the end of log files as they grow.
- **Virtualized Log List**: Efficiently handles large log files by loading only visible lines, supported by an LRU cache.
- **Powerful Highlighting**:
  - Define **Highlight Groups** for different log formats (e.g., Slf4j, standard).
  - Use **Regex** or plain text rules to colorize log lines.
  - Interactive highlights bar: see occurrence counts and jump to specific matches.
- **Multi-tab Interface**: Open and monitor multiple log files simultaneously in separate tabs.
- **Customizable**: Add, rename, or delete highlight groups and rules on the fly.
- **Cross-platform Configuration**: Automatically saves your preferences (last opened file, highlights) in standard OS locations:
  - **Linux**: `~/.config/tail4j/` (respects `XDG_CONFIG_HOME`)
  - **Windows**: `%APPDATA%\tail4j\`
  - **macOS**: `~/Library/Application Support/tail4j/`

## Technology Stack

- **Java 25**
- **JavaFX 23** (Controls, FXML)
- **Maven** for build management
- **Gson** for configuration persistence
- **SLF4J / Logback** for internal logging
- **JUnit 5** for testing

## Getting Started

### Prerequisites

- Java Development Kit (JDK) 25 or newer.

### Building from source

```bash
./mvnw clean install
```

### Running the application

```bash
./mvnw javafx:run
```

## License

This project is licensed under the **GNU General Public License v2.0**. See the `LICENSE` file for details.

## Author

Copyright (c) 2026 Robert von Burg <eitch@eitchnet.ch>
