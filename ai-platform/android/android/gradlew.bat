@echo off
setlocal enabledelayedexpansion
set APP_HOME=%~dp0
for /f "tokens=1,* delims==" %%A in (%APP_HOME%gradle\wrapper\gradle-wrapper.properties) do if "%%A"=="distributionUrl" set URL=%%B
set URL=%URL:\:=:%
for %%F in (%URL%) do set ZIPNAME=%%~nxF
set NAME=%ZIPNAME:.zip=%
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set CACHE=%GRADLE_USER_HOME%\wrapper\manual\%NAME%
set GRADLE=%CACHE%\%NAME%\bin\gradle.bat
if not exist "%GRADLE%" (
  if not exist "%CACHE%" mkdir "%CACHE%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing '%URL%' -OutFile '%CACHE%\%NAME%.zip'; Expand-Archive -Force '%CACHE%\%NAME%.zip' '%CACHE%'"
  if errorlevel 1 exit /b 1
)
call "%GRADLE%" -p "%APP_HOME%" %*
exit /b %ERRORLEVEL%
