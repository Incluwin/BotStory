@echo off
REM One-time BotStory server setup: checks Java 21, free ports, WZ data, disk space and MySQL,
REM writes .env, builds the server.
REM Safe to run again. Then start the server with start-server.bat. See docs\SETUP.md.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\setup\setup.ps1" %*
pause
