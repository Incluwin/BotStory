@echo off
REM Generates TownLife profiles (src\main\resources\agents\town-life\<profileId>.json) from WZ map geometry and the
REM bot navigation graph, or audits existing profiles against the map. Run from the server directory.
REM Usage: tools\town-life-profiles.bat generate <mapId>[=<profileId>] ... [--out <dir>] [--index] [--replace] [--admission AMBIENT|MANUAL_ONLY]
REM        tools\town-life-profiles.bat check [<profileId|mapId> ...]
if not exist target\Cosmic.jar (
    echo target\Cosmic.jar not found - run mvnw.cmd package -DskipTests first.
    exit /b 1
)
java -cp target\Cosmic.jar server.living.townlife.TownLifeProfileMain %*
