<#
.SYNOPSIS
    Compares two tell audit snapshots or builds (docs/BOT_CONCEPT_PLAN.md C2).

.DESCRIPTION
    Each side is a JSON file saved from GET /runtime/tells (its "line" is read), or a build sha (or its start) whose
    newest hourly audit.tells row comes from GET /runtime/tells/history on the admin port. With no sides given, the
    two newest builds in the history are compared. Prints each measure's value and verdict (G green, A amber, R red,
    - no data yet, i information only) on both sides, then bot deaths per class per bot-hour and per activity.
    The admin token is ADMIN_CONTROL_TOKEN from the environment, else from FriendServer\.env.

.EXAMPLE
    tools\tells-report.ps1
    tools\tells-report.ps1 -Before a435dc14b -After 5f0c2e1aa
    tools\tells-report.ps1 -Before before.json -After after.json
#>
param(
    [string]$Before,
    [string]$After,
    [int]$Port = 8480,
    [string]$Token = $env:ADMIN_CONTROL_TOKEN,
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\..\.env")
)

$ErrorActionPreference = "Stop"

$names = [ordered]@{
    "rc.cv" = "combat reaction, per-bot CV"; "rc.p5" = "combat reaction, 5th pct ms"
    "rh.cv" = "chat reply delay, CV"; "rh.p5" = "chat reply delay, 5th pct ms"
    "cc.cv" = "chat cadence, per-speaker CV"; "cr" = "same line again on a map"
    "st" = "idle bots stacked"; "po" = "most-online citizen, share"
    "fa" = "field AFKs over 2 min"; "se" = "session minutes, median"
    "ro" = "repeat walks retraced"; "pp" = "drinks at one HP point"; "br" = "recasts at one point"
}

function Get-History {
    if (-not $Token -and (Test-Path $EnvFile)) {
        $line = Get-Content $EnvFile | Where-Object { $_ -match '^ADMIN_CONTROL_TOKEN=' } | Select-Object -First 1
        if ($line) { $script:Token = $line.Substring("ADMIN_CONTROL_TOKEN=".Length).Trim() }
    }
    if (-not $Token) { throw "no ADMIN_CONTROL_TOKEN in the environment or $EnvFile" }
    $reply = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/runtime/tells/history" `
        -Headers @{ Authorization = "Bearer $Token" }
    return @($reply.rows)
}

# One audit.tells line: "b=<build> h=<hours> <code>=<value>:<verdict> ... d=<class>:<deaths>/<botHours>,... da=..."
function ConvertFrom-TellLine([string]$line) {
    $tell = [ordered]@{ build = ""; hours = ""; metrics = [ordered]@{}; deaths = [ordered]@{}; activity = [ordered]@{} }
    foreach ($token in $line -split ' ') {
        $key, $value = $token -split '=', 2
        switch ($key) {
            "b" { $tell.build = $value }
            "h" { $tell.hours = $value }
            "d" {
                foreach ($part in ($value -split ',' | Where-Object { $_ })) {
                    $class, $counts = $part -split ':', 2
                    $died, $botHours = $counts -split '/', 2
                    $tell.deaths[$class] = @{ deaths = [double]$died; hours = [double]$botHours }
                }
            }
            "da" {
                foreach ($part in ($value -split ',' | Where-Object { $_ })) {
                    $activity, $died = $part -split ':', 2
                    $tell.activity[$activity] = $died
                }
            }
            default {
                if ($value) {
                    $number, $verdict = $value -split ':', 2
                    $tell.metrics[$key] = "$number $verdict"
                }
            }
        }
    }
    return $tell
}

function Resolve-Side([string]$side, $history) {
    if ($side -and (Test-Path $side)) {
        return ConvertFrom-TellLine (Get-Content $side -Raw | ConvertFrom-Json).line
    }
    $rows = @($history | Where-Object { $_.line -like "b=$side*" })
    if ($rows.Count -eq 0) { throw "no audit.tells row for build '$side'" }
    return ConvertFrom-TellLine $rows[-1].line
}

$history = $null
if (-not ($Before -and $After -and (Test-Path $Before) -and (Test-Path $After))) {
    $history = Get-History
}
if (-not $Before -or -not $After) {
    $builds = @($history | ForEach-Object { ($_.line -split ' ')[0].Substring(2) } | Select-Object -Unique)
    if ($builds.Count -lt 2) { throw "the history holds $($builds.Count) build(s); name -Before and -After" }
    if (-not $After) { $After = $builds[-1] }
    if (-not $Before) { $Before = ($builds | Where-Object { $_ -ne $After })[-1] }
}
$old = Resolve-Side $Before $history
$new = Resolve-Side $After $history

"Tell audit: before $($old.build) ($($old.hours) h)  after $($new.build) ($($new.hours) h)"
""
$codes = @($names.Keys) + @(@($old.metrics.Keys) + @($new.metrics.Keys) | Where-Object { -not $names.Contains($_) } |
    Select-Object -Unique)
$codes | ForEach-Object {
    [pscustomobject]@{
        Measure = if ($names.Contains($_)) { $names[$_] } else { $_ }
        Before  = $old.metrics[$_]
        After   = $new.metrics[$_]
    }
} | Format-Table -AutoSize | Out-String -Width 200
"Deaths per bot-hour (deaths / bot-hours):"
$classes = @(@($old.deaths.Keys) + @($new.deaths.Keys) | Select-Object -Unique)
$classes | ForEach-Object {
    $a = $old.deaths[$_]; $b = $new.deaths[$_]
    [pscustomobject]@{
        Class  = $_
        Before = if ($a -and $a.hours -gt 0) { "{0:N3} ({1}/{2})" -f ($a.deaths / $a.hours), $a.deaths, $a.hours } else { "" }
        After  = if ($b -and $b.hours -gt 0) { "{0:N3} ({1}/{2})" -f ($b.deaths / $b.hours), $b.deaths, $b.hours } else { "" }
    }
} | Format-Table -AutoSize | Out-String -Width 200
"Deaths by activity: before $(($old.activity.GetEnumerator() | ForEach-Object { "$($_.Key) $($_.Value)" }) -join ', ')" +
    "; after $(($new.activity.GetEnumerator() | ForEach-Object { "$($_.Key) $($_.Value)" }) -join ', ')"
