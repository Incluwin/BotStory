@echo off
REM Starts the BotStory server: loads this folder's .env (written by setup.bat), then runs launch.bat,
REM which builds what changed and starts the server in this window. Stop it with !shutdown in game or
REM Ctrl+C here: both save everyone first. Closing the window kills it without saving.
setlocal
cd /d "%~dp0"
if not exist ".env" (
    echo No .env here yet. Run setup.bat first.
    pause
    exit /b 1
)
for /f "usebackq tokens=1,* eol=# delims==" %%A in (".env") do (
    if not "%%~A"=="" if not defined %%~A set "%%~A=%%~B"
)
call "%~dp0launch.bat"
endlocal
