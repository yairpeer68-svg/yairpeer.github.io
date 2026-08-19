@echo off
setlocal
cd /d "%~dp0"
if "%~1"=="" (
  echo Usage: IMPORT_SERVER_PAIRING_ON_WINDOWS.bat C:\path\to\extracted-pairing
  pause
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0IMPORT_SERVER_PAIRING_ON_WINDOWS.ps1" -PairingDir "%~1"
pause
