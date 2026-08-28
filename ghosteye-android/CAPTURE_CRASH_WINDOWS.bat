@echo off
setlocal
cd /d "%~dp0"
where adb >nul 2>nul
if errorlevel 1 (
  echo ADB was not found. Install Android Platform Tools or run this from a terminal where adb is available.
  exit /b 1
)
echo Clearing old Android logs...
adb logcat -c
adb shell am force-stop com.ghosteye.intelligence
adb shell monkey -p com.ghosteye.intelligence -c android.intent.category.LAUNCHER 1 >nul 2>nul
echo Ghost Eye started. Waiting 8 seconds...
timeout /t 8 /nobreak >nul
adb logcat -d -v threadtime AndroidRuntime:E *:S > ghost-eye-crash.txt
echo.
echo Saved: ghost-eye-crash.txt
echo Send that file if the app still closes unexpectedly.
endlocal
