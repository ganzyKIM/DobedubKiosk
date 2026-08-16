@echo off
rem Keep this file ASCII-only. cmd.exe re-reads a batch file by byte offset, so
rem non-ASCII bytes can desync the parser (see the note in the admin launcher).
rem Korean instructions live in 납품_LAN버전.md / PC관리자_사용법.md.
rem
rem ONE-TIME SETUP on the admin PC: installs the fleet server's dependencies.
rem Needs Node.js 22 LTS (https://nodejs.org/) and an internet connection.
rem The dependencies include a native module, so they must be installed on the
rem machine that will run the server - they cannot be copied from another OS.

cd /d "%~dp0server"

echo.
echo Installing fleet server dependencies (one time, internet required)...
echo.

call npm install

if errorlevel 1 (
  echo.
  echo [ERROR] npm install failed.
  echo Install Node.js 22 LTS from https://nodejs.org/ and run this file again.
  echo.
  pause
  exit /b 1
)

echo.
echo [OK] Server is ready.
echo Next: run the admin launcher batch file in this folder.
echo.
pause
