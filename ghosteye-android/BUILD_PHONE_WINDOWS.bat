@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ========================================
echo Ghost Eye Phone 10.1.1 - Verified Stability Release
echo Server: https://51.20.205.229
echo ========================================

set "FOUND_JAVA21="
for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do (
  if exist "%%~fD\bin\java.exe" set "FOUND_JAVA21=%%~fD"
)
if defined FOUND_JAVA21 (
  set "JAVA_HOME=!FOUND_JAVA21!"
  set "PATH=!JAVA_HOME!\bin;!PATH!"
  echo Using JDK 21: !JAVA_HOME!
)
java -version
if errorlevel 1 (
  echo Java is not available. Install Temurin JDK 21.
  exit /b 1
)

echo.
echo Cleaning and building debug APK...
call gradlew.bat clean assembleDebug -PAPI_BASE_URL=https://51.20.205.229
if errorlevel 1 (
  echo.
  echo BUILD FAILED
  exit /b 1
)

if not exist "app\build\outputs\apk\debug\app-debug.apk" (
  echo APK output was not created.
  exit /b 1
)

echo.
echo BUILD OK
echo APK: app\build\outputs\apk\debug\app-debug.apk
for %%F in ("app\build\outputs\apk\debug\app-debug.apk") do echo Size: %%~zF bytes
endlocal
