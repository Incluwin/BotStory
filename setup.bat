@echo off
REM One-time BotStory server setup: checks Java 21 and MySQL, writes .env, builds the server.
REM Safe to run again. Then start the server with start-server.bat. See docs\SETUP.md.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\setup\setup.ps1" %*
pause
