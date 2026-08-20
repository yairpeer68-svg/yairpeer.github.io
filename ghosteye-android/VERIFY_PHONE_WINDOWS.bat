@echo off
setlocal
cd /d "%~dp0"
where adb >nul 2>nul
if errorlevel 1 (
  echo ADB not found. Install Android Platform Tools.
  exit /b 1
)

adb get-state >nul 2>nul
if errorlevel 1 (
  echo No Android device is connected with USB debugging enabled.
  exit /b 1
)

adb logcat -c
adb shell am force-stop com.ghosteye.intelligence
adb shell monkey -p com.ghosteye.intelligence -c android.intent.category.LAUNCHER 1 >nul 2>nul
timeout /t 10 /nobreak >nul
adb logcat -d -v threadtime AndroidRuntime:E *:S > ghost-eye-androidruntime.txt
adb shell run-as com.ghosteye.intelligence cat files/last-crash.txt > ghost-eye-local-crash.txt 2>nul

findstr /C:"FATAL EXCEPTION" ghost-eye-androidruntime.txt >nul
if not errorlevel 1 (
  echo FAIL: AndroidRuntime crash detected.
  echo See ghost-eye-androidruntime.txt and ghost-eye-local-crash.txt
  exit /b 2
)

echo PASS: No AndroidRuntime crash detected after launch.
echo Logs saved for diagnostics.
endlocal
