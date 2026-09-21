@echo off
REM Regenerates scripts\config\population\fields.yaml from the server WZ XML tree (open-world maps with
REM mob spawn counts, average mob level and grinder capacity). Run from the server directory.
REM Usage: tools\population-fields.bat [wz-dir] [out.yaml]
if not exist target\Cosmic.jar (
    echo target\Cosmic.jar not found - run mvnw.cmd package -DskipTests first.
    exit /b 1
)
java -cp target\Cosmic.jar server.living.population.FieldCatalogMain %*
