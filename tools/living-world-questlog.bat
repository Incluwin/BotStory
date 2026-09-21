@echo off
REM Assigns client quest-log ids to living-world quests (quest-log-ids.yaml) and exports their text
REM for MapleEdit. Run from the server directory: tools\living-world-questlog.bat [content-dir] [export.json]
REM Then run MapleEdit\app\living_world_quests.py and deploy Quest.wz from the MapleEdit dashboard.
if not exist target\Cosmic.jar (
    echo target\Cosmic.jar not found - run mvnw.cmd package -DskipTests first.
    exit /b 1
)
java -Dwz-path=wz -cp target\Cosmic.jar server.living.config.QuestLogExportMain %*
