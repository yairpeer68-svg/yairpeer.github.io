@echo off
setlocal
cd /d "%~dp0"
set "GRADLE_VERSION=8.7"
set "GRADLE_SHA256=544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d"
set "CACHE=%USERPROFILE%\.gradle\manual-wrapper\gradle-%GRADLE_VERSION%"
set "GRADLE=%CACHE%\bin\gradle.bat"

if not exist "%GRADLE%" (
  echo Gradle %GRADLE_VERSION% not found locally. Downloading official distribution...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip=Join-Path $env:TEMP 'gradle-%GRADLE_VERSION%-bin.zip'; Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; $actual=(Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant(); if ($actual -ne '%GRADLE_SHA256%') { Remove-Item -Force $zip; throw ('Gradle SHA256 mismatch: ' + $actual) }; New-Item -ItemType Directory -Force (Join-Path $env:USERPROFILE '.gradle\manual-wrapper') | Out-Null; Expand-Archive -Force $zip (Join-Path $env:USERPROFILE '.gradle\manual-wrapper'); Remove-Item $zip"
  if errorlevel 1 (
    echo ERROR: Failed to download Gradle %GRADLE_VERSION%.
    exit /b 1
  )
)

call "%GRADLE%" %*
exit /b %ERRORLEVEL%
