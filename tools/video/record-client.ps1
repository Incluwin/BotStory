# Records the MapleStory client window (title "MapleCommons") to an MP4 with ffmpeg (gdigrab).
#   powershell -File tools\video\record-client.ps1 -Out <file.mp4> [-Seconds 60] [-Fps 30]
# Stops at -Seconds, or earlier when "<file.mp4>.stop" appears next to the output
# (ffmpeg gets a clean 'q' on stdin so the MP4 is finalized either way).
param([Parameter(Mandatory=$true)][string]$Out, [int]$Seconds = 60, [int]$Fps = 30)
$ff = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffmpeg.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
if (-not $ff) { $ff = (Get-Command ffmpeg -ErrorAction SilentlyContinue).Source }
if (-not $ff) { throw "ffmpeg not found (winget install Gyan.FFmpeg)" }
$stop = "$Out.stop"; Remove-Item $stop -ErrorAction SilentlyContinue
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $ff
$psi.Arguments = "-y -hide_banner -loglevel error -f gdigrab -framerate $Fps -draw_mouse 0 -i title=MapleCommons -t $Seconds -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -vf ""pad=ceil(iw/2)*2:ceil(ih/2)*2"" ""$Out"""
$psi.UseShellExecute = $false; $psi.RedirectStandardInput = $true; $psi.CreateNoWindow = $true
$p = [System.Diagnostics.Process]::Start($psi)
while (-not $p.HasExited) { Start-Sleep -Milliseconds 500; if (Test-Path $stop) { break } }
if (-not $p.HasExited) { $p.StandardInput.Write("q"); $p.StandardInput.Flush(); if (-not $p.WaitForExit(15000)) { $p.Kill() } }
Remove-Item $stop -ErrorAction SilentlyContinue
"recorded: $Out ($([math]::Round((Get-Item $Out).Length/1MB,1)) MB)"
