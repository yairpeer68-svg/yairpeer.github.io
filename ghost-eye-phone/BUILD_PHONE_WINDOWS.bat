@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ========================================
echo Ghost Eye Phone 10.0.0 - Single User
echo Server: https://51.20.205.229
echo ========================================

rem AGP 8.6 / Gradle 8.7 should run on JDK 21, not the JDK 25 that may be first in PATH.
set "FOUND_JAVA21="
for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do (
  if exist "%%~fD\bin\java.exe" set "FOUND_JAVA21=%%~fD"
)
if defined FOUND_JAVA21 (
  set "JAVA_HOME=!FOUND_JAVA21!"
  set "PATH=!JAVA_HOME!\bin;!PATH!"
  echo Using JDK 21: !JAVA_HOME!
) else (
  echo WARNING: JDK 21 was not found under Eclipse Adoptium.
  echo Current Java:
)
java -version
if errorlevel 1 (
  echo.
  echo Java is not available. Install Temurin JDK 21 and run this file again.
  exit /b 1
)

echo.
echo Building installable debug APK over HTTPS...
call gradlew.bat assembleDebug -PAPI_BASE_URL=https://51.20.205.229
if errorlevel 1 (
  echo.
  echo BUILD FAILED
  echo If the error mentions Java/JDK, make sure JDK 21 is installed.
  exit /b 1
)

echo.
echo BUILD OK
echo Installable APK: app\build\outputs\apk\debug\app-debug.apk
endlocal
