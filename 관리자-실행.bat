@echo off
rem Keep this file ASCII-only. cmd.exe re-reads the batch file by byte offset,
rem so non-ASCII bytes after "chcp 65001" desync the parser and split commands
rem ("powershell" once became "powers" + "hell"). Korean messages live in the .ps1.
chcp 65001 >nul
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-admin.ps1" %*
if errorlevel 1 (
  echo.
  echo [ERROR] admin launch did not finish cleanly. Check the messages above.
  pause
)
