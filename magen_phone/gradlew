#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
URL="https://services.gradle.org/distributions/gradle-8.13-wrapper.jar"
EXPECTED="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else echo "ERROR: sha256sum or shasum is required to verify Gradle Wrapper." >&2; exit 1
  fi
}

if [ ! -f "$JAR" ]; then
  mkdir -p "$(dirname "$JAR")"
  TMP="$JAR.tmp.$$"
  trap 'rm -f "$TMP"' EXIT HUP INT TERM
  echo "Bootstrapping verified Gradle 8.13 Wrapper..." >&2
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --proto '=https' --tlsv1.2 --output "$TMP" "$URL"
  elif command -v wget >/dev/null 2>&1; then
    wget --https-only -O "$TMP" "$URL"
  else
    echo "ERROR: curl or wget is required for the first wrapper bootstrap." >&2
    exit 1
  fi
  ACTUAL=$(hash_file "$TMP")
  if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo "ERROR: Gradle Wrapper SHA-256 mismatch." >&2
    echo "Expected: $EXPECTED" >&2
    echo "Actual:   $ACTUAL" >&2
    exit 1
  fi
  mv "$TMP" "$JAR"
  trap - EXIT HUP INT TERM
fi

ACTUAL=$(hash_file "$JAR")
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "ERROR: existing gradle-wrapper.jar failed SHA-256 verification." >&2
  exit 1
fi

exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
