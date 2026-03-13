#!/usr/bin/env bash
set -euo pipefail

# Resolve script directory (works for symlinks)
SOURCE="$0"
while [ -h "$SOURCE" ]; do
  DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
  SOURCE="$(readlink "$SOURCE")"
  [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
done
SCRIPT_DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"

# Locate bundled Zulu JRE (Linux x64); fall back to system Java if not found
JAVA_BIN=""
if [ -d "$SCRIPT_DIR/runtime" ]; then
  # Find first bin/java under runtime/*
  JAVA_CANDIDATE="$(find "$SCRIPT_DIR/runtime" -type f -path "*/bin/java" -print -quit 2>/dev/null || true)"
  if [ -n "${JAVA_CANDIDATE}" ] && [ -x "${JAVA_CANDIDATE}" ]; then
    JAVA_BIN="${JAVA_CANDIDATE}"
  fi
fi

if [ -z "$JAVA_BIN" ]; then
  if command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
  else
    echo "Error: No Java runtime found. Neither bundled runtime nor system Java is available." >&2
    exit 1
  fi
fi

APP_JAR="$SCRIPT_DIR/LumineLog.jar"
LIB_DIR="$SCRIPT_DIR/libs"

echo "Using java ${JAVA_BIN}"
exec "$JAVA_BIN" \
  --add-modules javafx.controls \
  --enable-native-access=javafx.graphics \
  -jar "$APP_JAR"
