@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo ========================================
echo Ghost Eye Phone 11.0.0 - Signed Release
echo ========================================

if not exist "keystore.properties" (
  echo ERROR: keystore.properties not found.
  echo Copy keystore.properties.example to keystore.properties and fill it locally.
  echo Do not send the keystore or its passwords to anyone.
  exit /b 1
)

set "FOUND_JAVA21="
for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do (
  if exist "%%~fD\bin\java.exe" set "FOUND_JAVA21=%%~fD"
)
if not defined FOUND_JAVA21 (
  echo ERROR: Temurin JDK 21 was not found.
  exit /b 1
)
set "JAVA_HOME=!FOUND_JAVA21!"
set "PATH=!JAVA_HOME!\bin;!PATH!"
java -version
if errorlevel 1 exit /b 1

call gradlew.bat --no-daemon clean assembleRelease bundleRelease
if errorlevel 1 (
  echo BUILD FAILED
  exit /b 1
)

if not exist "app\build\outputs\apk\release\app-release.apk" (
  echo ERROR: signed release APK output was not created.
  exit /b 1
)

if not exist "app\build\outputs\bundle\release\app-release.aab" (
  echo ERROR: signed release AAB output was not created.
  exit /b 1
)

echo BUILD SUCCESSFUL
echo APK: %CD%\app\build\outputs\apk\release\app-release.apk
echo AAB: %CD%\app\build\outputs\bundle\release\app-release.aab
for %%F in ("app\build\outputs\apk\release\app-release.apk") do echo APK Size: %%~zF bytes
for %%F in ("app\build\outputs\bundle\release\app-release.aab") do echo AAB Size: %%~zF bytes
endlocal
