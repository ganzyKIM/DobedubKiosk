@echo off
rem ASCII-only - see the run batch file header for why.
chcp 65001 >nul
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-admin.ps1" -Stop
pause
