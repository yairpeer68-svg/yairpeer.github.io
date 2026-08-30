@echo off
setlocal EnableExtensions
cd /d "%~dp0"
echo ========= 1. ENVIRONMENT =========
where java >nul 2>nul || (echo ERROR: Java not found in PATH.& pause & exit /b 1)
if not exist gradlew.bat (echo ERROR: gradlew.bat missing.& pause & exit /b 1)

echo ========= 2. CLEAN =========
call gradlew.bat --no-daemon clean
if errorlevel 1 goto :fail

echo ========= 3. RELEASE GUARD =========
python scripts\release_guard.py .
if errorlevel 1 goto :fail

echo ========= 4. BUILD APK =========
if exist keystore.properties (
  call gradlew.bat --no-daemon assembleRelease
  if errorlevel 1 goto :fail
  set "APK=app\build\outputs\apk\release\app-release.apk"
) else (
  echo INFO: keystore.properties not found; building installable signed debug APK.
  call gradlew.bat --no-daemon assembleDebug
  if errorlevel 1 goto :fail
  set "APK=app\build\outputs\apk\debug\app-debug.apk"
)

echo ========= 5. COPY TO DESKTOP =========
if not exist "%APK%" (echo ERROR: APK not produced.& goto :fail)
for /f "usebackq delims=" %%D in (`powershell -NoProfile -Command "[Environment]::GetFolderPath('Desktop')"`) do set "DESKTOP=%%D"
copy /y "%APK%" "%DESKTOP%\GhostEye-2.2.0.apk" >nul
if errorlevel 1 goto :fail

echo SUCCESS: %DESKTOP%\GhostEye-2.2.0.apk
pause
exit /b 0

:fail
echo BUILD FAILED. Nothing was installed or deleted.
pause
exit /b 1
