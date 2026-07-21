@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-tablet.ps1" %*
if errorlevel 1 (
  echo.
  echo [ERROR] setup did not finish cleanly. Check the messages above.
  pause
)
