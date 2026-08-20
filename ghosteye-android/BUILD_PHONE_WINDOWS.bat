@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo ========================================
echo Ghost Eye Phone 11.1.0 - Case Workspace + Intelligence Automation
echo ========================================

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
echo Using JDK 21: !JAVA_HOME!
java -version
if errorlevel 1 exit /b 1

echo.
echo Cleaning and building debug APK...
call gradlew.bat --no-daemon clean assembleDebug
if errorlevel 1 (
  echo.
  echo BUILD FAILED
  exit /b 1
)

if not exist "app\build\outputs\apk\debug\app-debug.apk" (
  echo ERROR: APK output was not created.
  exit /b 1
)

echo.
echo ========================================
echo BUILD SUCCESSFUL
echo ========================================
echo APK: %CD%\app\build\outputs\apk\debug\app-debug.apk
for %%F in ("app\build\outputs\apk\debug\app-debug.apk") do echo Size: %%~zF bytes
endlocal
