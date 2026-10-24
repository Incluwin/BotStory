@echo off
REM Imports the distilled guide claims (tools\guide-corpus\distill.py) into scripts\config\knowledge\guide-knowledge.json,
REM keeping only claims this server's data agrees with; writes tmp\guide-import-report.md.
REM Run from the server directory. Usage: tools\guide-import.bat corpus-dir [knowledge-dir] [report.md]
if not exist target\Cosmic.jar (
    echo target\Cosmic.jar not found - run mvnw.cmd package -DskipTests first.
    exit /b 1
)
java -cp target\Cosmic.jar server.knowledge.export.GuideImportMain %*
