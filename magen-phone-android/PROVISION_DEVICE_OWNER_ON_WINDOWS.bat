@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0PROVISION_DEVICE_OWNER_ON_WINDOWS.ps1"
pause
