@echo off
setlocal
cd /d "%~dp0"
echo [Magen] Starting Windows APK builder...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0BUILD_APK_ON_WINDOWS.ps1"
set "ERR=%ERRORLEVEL%"
if not "%ERR%"=="0" (
  echo.
  echo [Magen] Build failed with exit code %ERR%.
  pause
  exit /b %ERR%
)
echo.
echo [Magen] Done.
pause
