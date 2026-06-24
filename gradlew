#!/usr/bin/env sh
set -e

GRADLE_VERSION="9.3.1"
DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
CACHE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper-cache/gradle-${GRADLE_VERSION}"
ZIP_FILE="${CACHE_DIR}.zip"
INSTALL_DIR="${CACHE_DIR}/gradle-${GRADLE_VERSION}"

if [ ! -x "$INSTALL_DIR/bin/gradle" ]; then
	mkdir -p "$CACHE_DIR"
	if [ ! -f "$ZIP_FILE" ]; then
		curl -fL "$DIST_URL" -o "$ZIP_FILE"
	fi
	rm -rf "$INSTALL_DIR"
	unzip -q "$ZIP_FILE" -d "$CACHE_DIR"
fi

exec "$INSTALL_DIR/bin/gradle" "$@"