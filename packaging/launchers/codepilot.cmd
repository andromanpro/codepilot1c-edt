@echo off
setlocal EnableExtensions
rem CodePilot distribution launcher for Windows.
rem %~dp0 is the launcher directory; all paths are quoted for spaces.

set "SCRIPT_DIR=%~dp0"
set "JAR=%SCRIPT_DIR%..\lib\codepilot-cli.jar"
if not exist "%JAR%" (
  echo codepilot: distribution jar not found: "%JAR%" 1>&2
  exit /b 64
)

if defined CODEPILOT_JAVA (
  set "JAVA_COMMAND=%CODEPILOT_JAVA%"
) else if defined JAVA_HOME (
  set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_COMMAND=java.exe"
)

if not "%JAVA_COMMAND%"=="java.exe" if not exist "%JAVA_COMMAND%" (
  echo codepilot: Java executable not found: "%JAVA_COMMAND%" 1>&2
  exit /b 64
)

"%JAVA_COMMAND%" -jar "%JAR%" %*
exit /b %ERRORLEVEL%
