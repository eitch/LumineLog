@echo off
setlocal ENABLEEXTENSIONS ENABLEDELAYEDEXPANSION

REM Minimal shim to run the PowerShell launcher
set "SCRIPT_DIR=%~dp0"
set "PS1=%SCRIPT_DIR%start.ps1"

if not exist "%PS1%" (
  echo Error: PowerShell script not found: "%PS1%"
  exit /b 1
)

powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
set "EXITCODE=%ERRORLEVEL%"
endlocal & exit /b %EXITCODE%