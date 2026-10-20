# Builds dist\BotStory-Setup-<version>.exe from a BotStory-Kaentake checkout whose Release build is done.
#
#   powershell -ExecutionPolicy Bypass -File installer\build.ps1 -Kaentake C:\src\BotStory-Kaentake
#
# The checkout needs `cmake --preset release-win32` and `cmake --build --preset release-win32` run first
# (see its README). Needs Inno Setup 6 (https://jrsoftware.org/isdl.php).
param(
    [Parameter(Mandatory = $true)][string]$Kaentake
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$files = [ordered]@{
    'Launch Game.exe' = Join-Path $Kaentake 'build\Release\Kaentake.exe'
    'Kaentake.dll'    = Join-Path $Kaentake 'build\Release\Kaentake.dll'
    'Custom.wz'       = Join-Path $Kaentake 'src\Custom.wz'
}
foreach ($f in $files.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $f.Value)) { throw "missing $($f.Value): build the Release preset first" }
}

$iscc = @("${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
          "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe") | Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
if (-not $iscc) { throw 'Inno Setup 6 (ISCC.exe) not found: install it from https://jrsoftware.org/isdl.php' }

$payload = Join-Path $here 'payload'
if (Test-Path -LiteralPath $payload) { Remove-Item -LiteralPath $payload -Recurse -Force }
New-Item -ItemType Directory -Path $payload | Out-Null
foreach ($f in $files.GetEnumerator()) {
    Copy-Item -LiteralPath $f.Value -Destination (Join-Path $payload $f.Key)
    $h = (Get-FileHash -LiteralPath (Join-Path $payload $f.Key) -Algorithm SHA256).Hash.ToLower()
    Write-Host ("payload {0,-16} {1}" -f $f.Key, $h)
}

& $iscc "/O$(Join-Path $here 'dist')" (Join-Path $here 'setup.iss')
if ($LASTEXITCODE -ne 0) { throw "ISCC failed ($LASTEXITCODE)" }
