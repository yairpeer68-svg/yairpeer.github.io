@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo ========================================
echo Ghost Eye - Generate Release Keystore
echo ========================================
echo.
echo This creates release-keystore.jks locally.
echo Keep the keystore and passwords backed up securely. Never send them in chat.
echo.

set "FOUND_JAVA21="
for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do (
  if exist "%%~fD\bin\keytool.exe" set "FOUND_JAVA21=%%~fD"
)
if not defined FOUND_JAVA21 (
  echo ERROR: Temurin JDK 21 was not found.
  exit /b 1
)
set "JAVA_HOME=!FOUND_JAVA21!"
set "PATH=!JAVA_HOME!\bin;!PATH!"

if exist "release-keystore.jks" (
  echo ERROR: release-keystore.jks already exists. Refusing to overwrite it.
  exit /b 1
)

keytool -genkeypair -v -keystore "release-keystore.jks" -alias "ghost-eye" -keyalg RSA -keysize 4096 -validity 3650
if errorlevel 1 exit /b 1

echo.
echo Keystore created successfully.
echo Next: copy keystore.properties.example to keystore.properties and enter the same passwords locally.
endlocal
