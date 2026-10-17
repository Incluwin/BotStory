@echo off
REM Makes the Residents' client content on this machine, from your own client files: each Resident's
REM portrait (its look, rendered from your Character.wz) and the living-world quests in the quest log.
REM Run from the server directory after the server has created the Residents (a boot with
REM living_world.ENABLED), and again after RESIDENT_LOOK_SEED, a Resident's gear or the quests change:
REM     tools\living-world-client.bat "<client folder>" [--out "<folder>"] [--preview "<folder>"]
REM 1. ResidentPortraitExportMain: looks from the database, ids in portrait-npc-ids.yaml
REM 2. QuestLogExportMain: quest-log ids and text (after 1: a quest names its giver's portrait)
REM 3. tools\wz-patcher\botstory_wz.py residents: writes Npc.wz, String.wz, Quest.wz and Etc.wz
REM    (in place, originals kept in <client folder>\botstory-wz-backup\, unless --out is given)
setlocal
REM database settings as launch.bat reads them: FriendServer\.env, then config.yaml
set "ENV_FILE=%~dp0..\..\.env"
if exist "%ENV_FILE%" (
    for /f "usebackq tokens=1,* eol=# delims==" %%A in ("%ENV_FILE%") do (
        if not "%%~A"=="" if not defined %%~A set "%%~A=%%~B"
    )
)
if "%~1"=="" (
    echo usage: %~nx0 "client folder" [--out "folder"] [--preview "folder"]
    exit /b 2
)
if not exist target\Cosmic.jar (
    echo target\Cosmic.jar not found - run mvnw.cmd package -DskipTests first.
    exit /b 1
)
set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY where python >nul 2>nul && set "PY=python"
if not defined PY (
    echo Python 3.8 or newer is needed: https://www.python.org/downloads/
    exit /b 1
)
java -Dwz-path=wz -cp target\Cosmic.jar server.living.config.ResidentPortraitExportMain scripts\config\living-world target\resident-portraits.json || exit /b 1
java -Dwz-path=wz -cp target\Cosmic.jar server.living.config.QuestLogExportMain scripts\config\living-world target\living-quest-log.json || exit /b 1
%PY% tools\wz-patcher\botstory_wz.py residents --client "%~1" --export target %2 %3 %4 %5
