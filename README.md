# ![LumineLog Logo](src/main/resources/ch/eitchnet/luminelog/assets/LumineLog.svg) LumineLog

LumineLog is a modern, cross-platform log viewer application built with **JavaFX**. It provides a real-time "tail -f" experience with powerful highlighting and multi-file support.

## Features

- **Real-time Tailing**: Automatically follows the end of log files as they grow. The "Follow Tail" toggle allows jumping to the latest log entry instantly.
- **Virtualized Log List**: Efficiently handles large log files by loading only visible lines, supported by an LRU cache.
- **Search & Dynamic Highlighting**: 
  - Quickly search for terms and add them as highlights on the fly.
  - Supports **Regex** or plain text rules with customizable colors.
- **Powerful Highlighting Groups**:
  - Define **Highlight Groups** for different log formats (e.g., Slf4j, standard).
  - Add, rename, or delete highlight groups and rules on the fly.
  - Interactive highlights bar: see occurrence counts and jump to specific matches in a dedicated view.
- **Multi-tab Interface**: Open and monitor multiple log files simultaneously in separate tabs. Supports closing tabs and confirmation on exit.
- **File History**: Quickly reopen recently used files from the dedicated history menu.
- **Drag-and-Drop**: Easily open log files by dragging them onto the application window.
- **Font Size Customization**: Adjust the log display font size on the fly with a dedicated UI control.
- **Multi-selection & Clipboard**: Select multiple log lines and copy them to your clipboard via the context menu.
- **File Change Detection**: Automatically detects when a log file is replaced or truncated and reloads it.
- **Application Icon & About Dialog**: Professional look with a custom icon and an informative About dialog.
- **Cross-platform Configuration**: Automatically saves your preferences (last opened file, highlights) in standard OS locations:
  - **Linux**: `~/.config/LumineLog/` (respects `XDG_CONFIG_HOME`)
  - **Windows**: `%APPDATA%\LumineLog\`
  - **macOS**: `~/Library/Application Support/LumineLog/`

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
mvn clean install
```

### Running the application

```bash
mvn javafx:run
```

### Log view behavior and testing

The log view retains JavaFX `ListView` virtualization. Background indexing publishes versioned,
immutable index snapshots; pending updates are coalesced before they reach the JavaFX thread.
Following is remembered per file tab. Scrolling upward, navigating backward, or dragging a
selection pauses it; scrolling to the bottom, pressing End, or enabling Follow Tail resumes it.
Drag selection supports edge autoscrolling, and rows stay single-line at the selected font size.

Run regression tests with `mvn test`. JavaFX interaction tests need a graphical display; on
headless Linux, use `xvfb-run -a mvn test` with Xvfb installed.

Index snapshots preserve offsets and read boundaries, not historical file contents. If a file
is replaced or overwritten before a pending read, that read may fail and the next update reloads
the view. Change detection uses file identity, timestamps, size, and a bounded tail sample;
rewrites preserving all of these can evade detection. Initial indexing still runs while opening
a file, occurrence results are held in memory, and expensive regular expressions can remain
costly on very long lines.

### Building a package

```bash
# Clean:
mvn clean package
# Provision JDKS:
mvn -Pjdks
# Copy JARs:
mvn -Pjars
# Assemble Jlink distributions
mvn -Passemble
```

## Releasing 
```bash
mvn jreleaser:full-release -Djreleaser.dry.run=true
```

## License

This project is licensed under the **GNU General Public License v2.0**. See the `LICENSE` file for details.

## Author

Copyright (c) 2026 Robert von Burg <eitch@eitchnet.ch>
