#!/usr/bin/env bash
# Build the AI Platform APK on an Ubuntu machine.
#
# Installs the Flutter SDK and the Android command-line SDK into a local directory
# (nothing is installed system-wide) and produces an installable APK.
#
#   ./build-apk.sh                                   # app asks for the server on first launch
#   ./build-apk.sh https://api.example.com/api/v1    # server baked in
#
# Requirements: Ubuntu 22.04+, ~8 GB free disk, curl, unzip, git, and a JDK 17.
set -euo pipefail

API_BASE_URL="${1:-}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="${BUILD_TOOLS_DIR:-$HOME/.ai-platform-build}"
FLUTTER_VERSION="${FLUTTER_VERSION:-3.27.1}"
ANDROID_API="${ANDROID_API:-35}"
BUILD_TOOLS="${BUILD_TOOLS_VERSION:-35.0.0}"

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die(){ printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

command -v curl >/dev/null || die "curl is required: sudo apt-get install -y curl"
command -v unzip >/dev/null || die "unzip is required: sudo apt-get install -y unzip"
command -v git >/dev/null || die "git is required: sudo apt-get install -y git"

if ! command -v java >/dev/null; then
  die "A JDK is required: sudo apt-get install -y openjdk-17-jdk-headless"
fi

mkdir -p "$TOOLS"

log "Flutter SDK"
export FLUTTER_HOME="$TOOLS/flutter"
if [ ! -x "$FLUTTER_HOME/bin/flutter" ]; then
  curl -fL --progress-bar \
    "https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_${FLUTTER_VERSION}-stable.tar.xz" \
    -o "$TOOLS/flutter.tar.xz"
  tar -xJf "$TOOLS/flutter.tar.xz" -C "$TOOLS"
  rm -f "$TOOLS/flutter.tar.xz"
else
  echo "already present"
fi
git config --global --add safe.directory "$FLUTTER_HOME" 2>/dev/null || true
export PATH="$FLUTTER_HOME/bin:$PATH"

log "Android SDK"
export ANDROID_SDK_ROOT="$TOOLS/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  curl -fL --progress-bar \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    -o "$TOOLS/cmdline-tools.zip"
  unzip -q "$TOOLS/cmdline-tools.zip" -d "$ANDROID_SDK_ROOT/cmdline-tools"
  mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  rm -f "$TOOLS/cmdline-tools.zip"
else
  echo "already present"
fi
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

log "Android packages"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "platform-tools" "platforms;android-${ANDROID_API}" "build-tools;${BUILD_TOOLS}" >/dev/null
flutter config --android-sdk "$ANDROID_SDK_ROOT" >/dev/null

log "Dependencies"
cd "$HERE"
flutter pub get

log "Verification"
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test

log "Build"
version="$(grep '^version:' pubspec.yaml | cut -d' ' -f2 | cut -d'+' -f1)"
build="$(grep '^version:' pubspec.yaml | cut -d'+' -f2)"
defines=(--dart-define=APP_VERSION="$version" --dart-define=APP_BUILD_NUMBER="$build")
if [ -n "$API_BASE_URL" ]; then
  defines+=(--dart-define=API_BASE_URL="$API_BASE_URL")
  echo "Server baked in: $API_BASE_URL"
else
  echo "No server baked in; the app will ask on first launch."
fi

if [ -f android/key.properties ]; then
  echo "Signing with android/key.properties"
  flutter build apk --release "${defines[@]}"
  flutter build appbundle --release "${defines[@]}"
else
  cat >&2 <<'WARN'

  No android/key.properties found. Building a debug-signed release APK.
  It installs by sideloading, but cannot be published to Play or upgraded in place.
  To create a real upload key, see the "Signing key" section of docs/ANDROID.md.

WARN
  flutter build apk --release "${defines[@]}" -PALLOW_UNSIGNED_RELEASE=true
fi

log "Result"
find build/app/outputs -name '*.apk' -o -name '*.aab' | while read -r f; do
  printf '%s\n  %s\n' "$f" "$(sha256sum "$f" | cut -d' ' -f1)"
done
echo
echo "Install on a connected device:  adb install -r build/app/outputs/flutter-apk/app-release.apk"
