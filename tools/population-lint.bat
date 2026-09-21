@echo off
REM Validates population content (cohorts, careers, names, voices, guild names, regions, fields, chatter, market).
REM Run from the server directory. Usage: tools\population-lint.bat [population-dir] [living-dir]
if not exist target\Cosmic.jar (
    echo target\Cosmic.jar not found - run mvnw.cmd package -DskipTests first.
    exit /b 1
)
java -cp target\Cosmic.jar server.living.population.PopulationLintMain %*
