@echo off
setlocal EnableExtensions
rem Development packaging prerequisite: no CLI jar is needed to run this wrapper.

if not defined CODEPILOT_EDT_HOME if defined EDT_HOME set "CODEPILOT_EDT_HOME=%EDT_HOME%"
if not defined CODEPILOT_EDT_HOME (
  echo codepilot-edt-headless: set CODEPILOT_EDT_HOME to the EDT Eclipse directory 1>&2
  exit /b 64
)

set "CODEPILOT_EDT_LAUNCHER=%CODEPILOT_EDT_EXECUTABLE%"
if not defined CODEPILOT_EDT_LAUNCHER if exist "%CODEPILOT_EDT_HOME%\1cedt.exe" set "CODEPILOT_EDT_LAUNCHER=%CODEPILOT_EDT_HOME%\1cedt.exe"
if not defined CODEPILOT_EDT_LAUNCHER if exist "%CODEPILOT_EDT_HOME%\eclipse.exe" set "CODEPILOT_EDT_LAUNCHER=%CODEPILOT_EDT_HOME%\eclipse.exe"
if not defined CODEPILOT_EDT_LAUNCHER (
  echo codepilot-edt-headless: no EDT launcher found under %CODEPILOT_EDT_HOME% 1>&2
  echo codepilot-edt-headless: set CODEPILOT_EDT_EXECUTABLE explicitly 1>&2
  exit /b 64
)

if not defined CODEPILOT_APPLICATION set "CODEPILOT_APPLICATION=com.codepilot1c.core.headless"
"%CODEPILOT_EDT_LAUNCHER%" -application "%CODEPILOT_APPLICATION%" %*
exit /b %ERRORLEVEL%
