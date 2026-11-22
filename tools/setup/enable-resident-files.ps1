# Called by tools\living-world-client.bat after it has made the Residents' files in a client folder: turns the server
# mod resident_files on in this server's .env (MOD_RESIDENT_FILES=on) when the line is blank or missing, so Residents
# show their own portraits and their quest icons are clickable from the next server start. A value already there wins:
# MOD_RESIDENT_FILES=off stays off, for a server that also has clients without those files (they close on them).
param([Parameter(Mandatory = $true)][string]$EnvFile)
$ErrorActionPreference = 'Stop'
$name = 'MOD_RESIDENT_FILES'
if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Host "No .env at $EnvFile. To show the Residents' own portraits, start the server with $name=on"
    Write-Host '(with Docker: under environment: in docker-compose.yml, or in .env).'
    exit 0
}
$lines = [Collections.Generic.List[string]]([IO.File]::ReadAllLines($EnvFile))
$index = -1
for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match "^\s*$name=(.*)$") { $index = $i; $value = $Matches[1].Trim(); break } }
if ($index -ge 0 -and $value) {
    Write-Host "Keeping $name=$value from .env."
    exit 0
}
if ($index -ge 0) { $lines[$index] = "$name=on" } else { $lines.Add("$name=on") }
# UTF-8 without a BOM: start-server.bat reads .env with cmd's for /f, which would take a BOM as part of the first line
[IO.File]::WriteAllLines($EnvFile, $lines, (New-Object Text.UTF8Encoding $false))
Write-Host "Set $name=on in .env: restart the server, and Residents show their own portraits and clickable quest icons."
Write-Host "Every client that connects needs this step. If one has not run it, set $name=off: it would close on them."
