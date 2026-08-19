@echo off
setlocal enabledelayedexpansion
set "DIR=%~dp0"
set "GRADLE_VERSION=8.7"
set "CACHE=%USERPROFILE%\.gradle\manual-wrapper\gradle-%GRADLE_VERSION%"
set "GRADLE=%CACHE%\bin\gradle.bat"
if not exist "%GRADLE%" (
  echo Gradle %GRADLE_VERSION% not found locally. Downloading official distribution...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip=$env:TEMP+'\gradle-%GRADLE_VERSION%-bin.zip'; Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; New-Item -ItemType Directory -Force '%USERPROFILE%\.gradle\manual-wrapper' | Out-Null; Expand-Archive -Force $zip '%USERPROFILE%\.gradle\manual-wrapper'; Remove-Item $zip"
  if errorlevel 1 exit /b 1
)
call "%GRADLE%" -p "%DIR%" %*
exit /b %ERRORLEVEL%
