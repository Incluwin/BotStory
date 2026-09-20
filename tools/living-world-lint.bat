@echo off
REM Validates the living-world content (personas, quests, contracts, arcs, rumors, loadouts, phrase packs).
REM Run from the server directory. Usage: tools\living-world-lint.bat [content-dir]
REM Exit code 1 on parse errors or ERROR findings; warnings (flags never read, etc.) do not fail.
if not exist target\Cosmic.jar (
    echo target\Cosmic.jar not found - run mvnw.cmd package -DskipTests first.
    exit /b 1
)
java -cp target\Cosmic.jar server.living.config.ContentLintMain %*
