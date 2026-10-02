@echo off
REM Regenerates scripts\config\knowledge\server-knowledge.json from the server WZ XML tree and scripts
REM (maps, mobs, items, NPCs, quests, script-spawned mobs). Drops and shops are read from the DB at boot.
REM Run from the server directory. Usage: tools\knowledge-export.bat [server-dir] [out.json]
if not exist target\Cosmic.jar (
    echo target\Cosmic.jar not found - run mvnw.cmd package -DskipTests first.
    exit /b 1
)
java -cp target\Cosmic.jar server.knowledge.export.KnowledgeExportMain %*
