@echo off
setlocal
cd /d "%~dp0"
echo ========================================
echo Ghost Eye Android 9.3.1
echo Server: https://51.20.205.229
echo ========================================
call gradlew.bat assembleRelease -PAPI_BASE_URL=https://51.20.205.229
if errorlevel 1 (
  echo.
  echo BUILD FAILED
  exit /b 1
)
echo.
echo BUILD OK
echo APK: app\build\outputs\apk\release\app-release-unsigned.apk
endlocal
