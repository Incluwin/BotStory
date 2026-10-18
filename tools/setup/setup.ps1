# One-time BotStory server setup for Windows; setup.bat in the repo root runs it. Safe to run again:
# an existing .env is kept and only its blank database login is filled in.
#   1. Java 21 or newer (offers winget install of Amazon Corretto 21)
#   2. MySQL 8 on port 3306: an installed server, or one this script starts in Docker
#   3. .env with the database login (DB_USER, DB_PASS), checked against MySQL
#   4. the first build (target\Cosmic.jar)
param([switch]$SkipBuild)

$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $Root
$EnvFile = Join-Path $Root '.env'
$Container = 'botstory-mysql'

function Say([string]$text) { Write-Host "[setup] $text" }
function Stop-Setup([string]$text) { Write-Host "[setup] $text" -ForegroundColor Red; exit 1 }
function Confirm-Step([string]$question) { (Read-Host "[setup] $question [y/N]") -match '^(y|yes)$' }
function Test-Command([string]$name) { [bool](Get-Command $name -ErrorAction SilentlyContinue) }

function New-Secret([int]$length) {
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    $bytes = New-Object byte[] $length
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

function Read-Secret([string]$prompt) {
    $secure = Read-Host "[setup] $prompt" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

function Get-EnvValue([string]$name) {
    foreach ($line in [IO.File]::ReadAllLines($EnvFile)) {
        if ($line -match "^\s*$name=(.*)$") { return $Matches[1].Trim() }
    }
    return ''
}

# Writes NAME=value in .env, replacing the NAME line when there is one. UTF-8 without a BOM:
# start-server.bat reads the file with cmd's for /f, which would take a BOM as part of the first line.
function Set-EnvValue([string]$name, [string]$value) {
    $lines = [Collections.Generic.List[string]]([IO.File]::ReadAllLines($EnvFile))
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match "^\s*$name=") { $index = $i; break } }
    if ($index -ge 0) { $lines[$index] = "$name=$value" } else { $lines.Add("$name=$value") }
    [IO.File]::WriteAllLines($EnvFile, $lines, (New-Object Text.UTF8Encoding $false))
}

function Get-JavaMajor {
    if (-not (Test-Command 'java')) { return 0 }
    $text = (& java -version 2>&1 | Out-String)
    if ($text -match 'version "1\.(\d+)') { return [int]$Matches[1] }
    if ($text -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

function Find-MysqlClient {
    $command = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $installed = Get-ChildItem "$env:ProgramFiles\MySQL\MySQL Server*\bin\mysql.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($installed) { return $installed.FullName }
    return $null
}

function Test-LocalPort([int]$port) {
    $client = New-Object Net.Sockets.TcpClient
    try { return ($client.ConnectAsync('127.0.0.1', $port).Wait(1500) -and $client.Connected) }
    catch { return $false }
    finally { $client.Dispose() }
}

# Runs one statement as $user; the password goes through MYSQL_PWD, never the command line.
function Invoke-Mysql([string]$client, [string]$user, [string]$password, [string]$sql) {
    $env:MYSQL_PWD = $password
    try {
        $output = & $client --protocol=TCP -h 127.0.0.1 -P 3306 -u $user -N -e $sql 2>&1 | Out-String
        return @{ Ok = ($LASTEXITCODE -eq 0); Output = $output.Trim() }
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

function Show-MysqlHelp {
    Say 'No MySQL server answers on port 3306. Install one of these, then run setup.bat again:'
    Say '  - MySQL Community Server 8: https://dev.mysql.com/downloads/installer/'
    Say '    (pick "Server only", keep port 3306, choose a root password and remember it)'
    Say '  - or Docker Desktop (https://www.docker.com/products/docker-desktop/); setup.bat then starts'
    Say '    MySQL in a container for you.'
}

Say "BotStory setup in $Root"

# --- 1. Java 21 ---------------------------------------------------------------------------------
$java = Get-JavaMajor
if ($java -lt 21) {
    if ($java -eq 0) { Say 'Java was not found.' } else { Say "Java $java was found; BotStory needs Java 21 or newer." }
    if ((Test-Command 'winget') -and (Confirm-Step 'Install Amazon Corretto 21 (Java) with winget now?')) {
        winget install -e --id Amazon.Corretto.21.JDK --accept-package-agreements --accept-source-agreements
        Say 'Java is installed. Close this window and run setup.bat again, so the new Java is on PATH.'
        exit 0
    }
    Stop-Setup 'Install Java 21 (https://aws.amazon.com/corretto), then run setup.bat again.'
}
Say "Java $java found."

# --- 2. .env ------------------------------------------------------------------------------------
if (Test-Path $EnvFile) {
    Say 'Keeping your existing .env; only a blank database login is filled in.'
} else {
    Copy-Item (Join-Path $Root '.env.example') $EnvFile
    Say 'Created .env from .env.example.'
}
$dbUser = Get-EnvValue 'DB_USER'
if (-not $dbUser) { $dbUser = 'root' }
$dbPass = Get-EnvValue 'DB_PASS'

# --- 3. MySQL -----------------------------------------------------------------------------------
$client = Find-MysqlClient
if (-not (Test-LocalPort 3306)) {
    $docker = $false
    if (Test-Command 'docker') { & docker info --format '{{.ServerVersion}}' *> $null; $docker = ($LASTEXITCODE -eq 0) }
    if (-not $docker) { Show-MysqlHelp; exit 1 }
    $existing = (& docker ps -a --filter "name=^$Container$" --format '{{.Status}}' 2>$null | Out-String).Trim()
    if ($existing) {
        Say "Starting the existing $Container container."
        & docker start $Container | Out-Null
    } else {
        if (-not (Confirm-Step 'No MySQL found on port 3306. Start MySQL 8.4 in Docker for BotStory?')) { Show-MysqlHelp; exit 1 }
        $dbUser = 'root'
        if (-not $dbPass) { $dbPass = New-Secret 24 }
        $env:MYSQL_ROOT_PASSWORD = $dbPass
        & docker run -d --name $Container --restart unless-stopped -e MYSQL_ROOT_PASSWORD -e MYSQL_DATABASE=cosmic `
            -p 127.0.0.1:3306:3306 -v botstory-mysql:/var/lib/mysql mysql:8.4 | Out-Null
        Remove-Item Env:MYSQL_ROOT_PASSWORD -ErrorAction SilentlyContinue
        if ($LASTEXITCODE -ne 0) { Stop-Setup 'docker run failed; see the message above.' }
        Say "MySQL runs in the Docker container $Container (data in the volume botstory-mysql)."
    }
    Say 'Waiting for MySQL to accept connections...'
    $deadline = (Get-Date).AddMinutes(3)
    while (-not (Test-LocalPort 3306)) {
        if ((Get-Date) -gt $deadline) { Stop-Setup "MySQL did not open port 3306 within 3 minutes: docker logs $Container" }
        Start-Sleep -Seconds 2
    }
    Start-Sleep -Seconds 5
}
Say 'MySQL answers on port 3306.'

if ($dbPass -match '"') { Stop-Setup 'The DB_PASS in .env contains a double quote, which the Windows start script cannot pass on. Pick another password.' }
if ($client) {
    for ($attempt = 1; $true; $attempt++) {
        if (-not $dbPass) { $dbPass = Read-Secret "MySQL password for user '$dbUser'" }
        $result = Invoke-Mysql $client $dbUser $dbPass 'CREATE DATABASE IF NOT EXISTS cosmic'
        if ($result.Ok) { break }
        Say "MySQL refused the login: $($result.Output -replace '^mysql(\.exe)? : ', '')"
        if ($attempt -ge 3) { Stop-Setup "Check the MySQL user and password (DB_USER/DB_PASS in .env), then run setup.bat again." }
        $dbPass = ''
    }
    Say "Logged in to MySQL as '$dbUser'; the 'cosmic' database is ready."
} else {
    if (-not $dbPass) { $dbPass = Read-Secret "MySQL password for user '$dbUser'" }
    Say "mysql.exe was not found, so the login is checked on the first server start (it creates the 'cosmic' database)."
}
if ($dbPass -match '"') { Stop-Setup 'That password contains a double quote, which the Windows start script cannot pass on. Pick another password.' }
Set-EnvValue 'DB_USER' $dbUser
Set-EnvValue 'DB_PASS' $dbPass
Say 'Saved the database login in .env.'

# --- 4. First build -----------------------------------------------------------------------------
if (-not $SkipBuild) {
    Say 'Building the server. The first build downloads its libraries and takes a few minutes...'
    & (Join-Path $Root 'mvnw.cmd') -B -ntp package -DskipTests
    if ($LASTEXITCODE -ne 0) { Stop-Setup 'The build failed; see the messages above.' }
    Say 'Built target\Cosmic.jar.'
}

Say ''
Say 'Setup is done. Start the server with start-server.bat; it is ready when the window says'
Say '"Cosmic is now online". Then set up the game client: docs/SETUP.md, section "The game client".'
Say 'Log in as admin / admin (its character Admin is a GM), or type any new username and password:'
Say 'accounts are created on first login. Change the admin password before other people connect.'
if (Confirm-Step 'Start the server now?') {
    Start-Process -FilePath (Join-Path $Root 'start-server.bat') -WorkingDirectory $Root
}
