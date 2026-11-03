#!/usr/bin/env sh
set -eu
GRADLE_VERSION=8.13
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-${GRADLE_VERSION}-bin/mosaic-bootstrap"
GRADLE_BIN="$GRADLE_HOME_DIR/gradle-${GRADLE_VERSION}/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$GRADLE_HOME_DIR"
  ZIP="$GRADLE_HOME_DIR/gradle-${GRADLE_VERSION}-bin.zip"
  if [ ! -f "$ZIP" ]; then
    command -v curl >/dev/null 2>&1 || { echo "curl is required to download Gradle" >&2; exit 1; }
    curl -fL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$ZIP"
  fi
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required to extract Gradle" >&2; exit 1; }
  unzip -q -o "$ZIP" -d "$GRADLE_HOME_DIR"
fi
exec "$GRADLE_BIN" "$@"
