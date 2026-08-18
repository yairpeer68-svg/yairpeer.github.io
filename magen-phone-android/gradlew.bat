@echo off
setlocal EnableExtensions
set "APP_HOME=%~dp0"
set "JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
set "URL=https://services.gradle.org/distributions/gradle-8.13-wrapper.jar"
set "EXPECTED=81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"

if not exist "%JAR%" (
  if not exist "%APP_HOME%gradle\wrapper" mkdir "%APP_HOME%gradle\wrapper"
  echo Bootstrapping verified Gradle 8.13 Wrapper...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri '%URL%' -OutFile '%JAR%.tmp'; $h=(Get-FileHash -Algorithm SHA256 '%JAR%.tmp').Hash.ToLowerInvariant(); if($h -ne '%EXPECTED%'){Remove-Item -Force '%JAR%.tmp'; throw 'Gradle Wrapper SHA-256 mismatch: '+$h}; Move-Item -Force '%JAR%.tmp' '%JAR%'"
  if errorlevel 1 exit /b 1
)

for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%JAR%').Hash.ToLowerInvariant()"') do set "ACTUAL=%%H"
if /I not "%ACTUAL%"=="%EXPECTED%" (
  echo ERROR: existing gradle-wrapper.jar failed SHA-256 verification. 1>&2
  exit /b 1
)

java -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
