@echo off
@title Cosmic
setlocal

REM --- Load secrets from FriendServer\.env if present. Any var set by the
REM ---   caller (e.g. start_all.bat) wins over what's in .env, which wins over
REM ---   the defaults in config.yaml.
set "ENV_FILE=%~dp0..\.env"
if exist "%ENV_FILE%" (
    for /f "usebackq tokens=1,* eol=# delims==" %%A in ("%ENV_FILE%") do (
        if not "%%~A"=="" if not defined %%~A set "%%~A=%%~B"
    )
)

REM --- Incremental rebuild. Maven skips compile when sources are unchanged;
REM ---   total overhead ~8s on a no-op build. Fails fast on compile errors
REM ---   instead of launching a stale jar.
echo [build] mvnw package (incremental)...
call mvnw.cmd package -DskipTests
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed. Fix the errors above, then restart.
    pause
    exit /b 1
)

if not exist logs\gc mkdir logs\gc
if not exist logs\heapdumps mkdir logs\heapdumps
if not exist logs\crash mkdir logs\crash
java -Xmx2048m %JAVA_OPTS% -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs\heapdumps -XX:+ExitOnOutOfMemoryError -XX:ErrorFile=logs\crash\hs_err_pid%%p.log -Xlog:gc*,safepoint:file=logs/gc/gc.log:time,uptime,level,tags:filecount=10,filesize=20M -Dagents.scheduler.mode=central-sequential -Dwz-path=wz -jar target\Cosmic.jar

endlocal
pause
