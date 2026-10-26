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
    echo         This window closes in 10 minutes; press a key to close it now.
    timeout /t 600
    exit /b 0
)

if not exist logs\gc mkdir logs\gc
if not exist logs\heapdumps mkdir logs\heapdumps
if not exist logs\crash mkdir logs\crash
java -Xmx2048m %JAVA_OPTS% -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs\heapdumps -XX:+ExitOnOutOfMemoryError -XX:ErrorFile=logs\crash\hs_err_pid%%p.log -Xlog:gc*,safepoint:file=logs/gc/gc.log:time,uptime,level,tags:filecount=10,filesize=20M -Dwz-path=wz -jar target\Cosmic.jar
set "COSMIC_EXIT=%ERRORLEVEL%"

REM --- Every restart opens a new console, so this one must close by itself: at once after a clean
REM ---   shutdown (exit 0), two minutes after any other exit. Logs are in logs\, JVM crash files in
REM ---   logs\crash\. It always exits 0 (nothing reads it) so a terminal that keeps a tab open after
REM ---   a failure closes it too. cmd reads this file by byte offset as it runs: edit it only while
REM ---   no server runs from it, or that console resumes at a different line when java exits.
if not "%COSMIC_EXIT%"=="0" (
    echo.
    echo [Cosmic] Server exited with code %COSMIC_EXIT%.
    echo          This window closes in 2 minutes; press a key to close it now.
    timeout /t 120
)
endlocal
exit /b 0
