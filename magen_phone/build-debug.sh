#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
command -v java >/dev/null || { echo 'Java 17 is required.'; exit 1; }
java -version 2>&1 | head -1
chmod +x ./gradlew
./gradlew --no-daemon clean testDebugUnitTest assembleDebug
APK="$(find app/build/outputs/apk/debug -type f -name '*.apk' | head -1 || true)"
[[ -n "$APK" ]] && echo "APK: $APK"
