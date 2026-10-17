@echo off
rem Add the BotStory content to a client's WZ files, then check the result.
rem usage: patch-client.bat "<client folder>"
setlocal
if "%~1"=="" (
    echo usage: %~nx0 "client folder"
    exit /b 2
)
set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY where python >nul 2>nul && set "PY=python"
if not defined PY (
    echo Python 3.8 or newer is needed: https://www.python.org/downloads/
    exit /b 1
)
%PY% "%~dp0botstory_wz.py" patch --client "%~1" || exit /b 1
%PY% "%~dp0botstory_wz.py" check --client "%~1"
