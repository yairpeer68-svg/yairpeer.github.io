#!/data/data/com.termux/files/usr/bin/bash
set -e
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'

echo -e "${GREEN}🎮 GameMode VPN v2.0 APK Builder${NC}"
echo "======================================="

# Java
if ! command -v javac &> /dev/null; then
    echo -e "${RED}❌ Java חסר: pkg install openjdk-17${NC}"; exit 1
fi
echo -e "${GREEN}✓ Java OK${NC}"

# Android SDK
ANDROID_SDK="${ANDROID_HOME:-$HOME/android-sdk}"
if [ ! -d "$ANDROID_SDK" ]; then
    echo -e "${RED}❌ Android SDK חסר ב: $ANDROID_SDK${NC}"; exit 1
fi
echo -e "${GREEN}✓ Android SDK: $ANDROID_SDK${NC}"

# NDK
NDK_PATH="$ANDROID_SDK/ndk"
if [ ! -d "$NDK_PATH" ]; then
    echo -e "${YELLOW}⚠️  NDK לא נמצא - מוריד...${NC}"
    $ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager "ndk;25.2.9519653"
fi
echo -e "${GREEN}✓ NDK OK${NC}"

# CMake
CMAKE_PATH="$ANDROID_SDK/cmake"
if [ ! -d "$CMAKE_PATH" ]; then
    echo -e "${YELLOW}⚠️  CMake לא נמצא - מוריד...${NC}"
    $ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager "cmake;3.22.1"
fi
echo -e "${GREEN}✓ CMake OK${NC}"

# local.properties
echo "sdk.dir=$ANDROID_SDK" > local.properties
echo "ndk.dir=$NDK_PATH/$(ls $NDK_PATH | tail -1)" >> local.properties

# Build
echo -e "\n${GREEN}🔨 בונה APK (כולל C++ NDK)...${NC}"
chmod +x gradlew
./gradlew assembleDebug --no-daemon 2>&1 | grep -E "(BUILD|FAIL|error:|warning:|>)" | tail -30

# Find and copy APK
APK=$(find . -name "*debug*.apk" 2>/dev/null | head -1)
if [ -n "$APK" ]; then
    DEST="$HOME/storage/downloads/PingBooster_v2.apk"
    cp "$APK" "$DEST" 2>/dev/null || cp "$APK" "$HOME/PingBooster_v2.apk"
    echo -e "\n${GREEN}✅ בנייה הושלמה!${NC}"
    echo -e "${YELLOW}📦 $DEST${NC}"
    ls -lh "$APK"
else
    echo -e "${RED}❌ APK לא נמצא. בדוק שגיאות.${NC}"; exit 1
fi
