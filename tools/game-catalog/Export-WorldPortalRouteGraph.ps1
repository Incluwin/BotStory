param(
    [string]$MapRoot = "wz/Map.wz/Map",
    [string]$OutputPath = "src/main/resources/agents/catalogs/world-portal-route-graph.json"
)

# Standard (unscripted) portal edges of every continent outside Victoria Island, read straight from WZ.
# Same rule as Export-VictoriaPortalRouteGraph.ps1: a portal with a target map (not 999999999), no script,
# leading to another map. An edge counts only when both maps lie in the same continent group below and
# neither lies in an excluded range (the ferry, crane and boat ride maps 2000900xx). Victoria Island
# (100000000-129999999) stays in victoria-portal-route-graph.json. The group table is written into the
# catalog, and AgentWorldPortalRouteGraphFreshnessTest re-derives the edges from WZ with it.

$ErrorActionPreference = "Stop"

$groups = @(
    [ordered]@{ id = "ereve"; firstMapId = 130000000; endMapId = 140000000 },
    [ordered]@{ id = "rien"; firstMapId = 140000000; endMapId = 150000000 },
    [ordered]@{ id = "ossyria"; firstMapId = 200000000; endMapId = 310000000 },
    [ordered]@{ id = "singapore"; firstMapId = 540000000; endMapId = 550000000 },
    [ordered]@{ id = "malaysia"; firstMapId = 550000000; endMapId = 560000000 },
    [ordered]@{ id = "masteria"; firstMapId = 600000000; endMapId = 620000000 },
    [ordered]@{ id = "zipangu"; firstMapId = 800000000; endMapId = 810000000 }
)
$excludedRanges = @(
    [ordered]@{ firstMapId = 200090000; endMapId = 200100000; reason = "ferry, crane and boat ride maps" }
)

function Get-Group([int]$mapId) {
    foreach ($range in $excludedRanges) {
        if ($mapId -ge $range.firstMapId -and $mapId -lt $range.endMapId) { return $null }
    }
    foreach ($group in $groups) {
        if ($mapId -ge $group.firstMapId -and $mapId -lt $group.endMapId) { return $group.id }
    }
    return $null
}

if (-not (Test-Path -LiteralPath $MapRoot)) {
    throw "WZ map directory not found: $MapRoot"
}

$portalBlock = [regex]'(?s)<imgdir name="portal">(.*?)\n  </imgdir>'
$portalEntry = [regex]'(?s)<imgdir name="\d+">(.*?)</imgdir>'
$targetMap = [regex]'<int name="tm" value="(-?\d+)"/>'
$script = [regex]'<string name="script" value="([^"]*)"/>'

$edgeKeys = New-Object 'System.Collections.Generic.HashSet[long]'
$sourceLines = New-Object System.Collections.Generic.List[string]
$files = Get-ChildItem -LiteralPath $MapRoot -Recurse -Filter "*.img.xml" |
    Where-Object { $_.Name -match '^\d+\.img\.xml$' } |
    Sort-Object { [int]($_.Name -replace '\.img\.xml$', '') }
foreach ($file in $files) {
    $mapId = [int]($file.Name -replace '\.img\.xml$', '')
    $group = Get-Group $mapId
    if ($null -eq $group) { continue }
    $text = [System.IO.File]::ReadAllText($file.FullName)
    $sourceLines.Add("$mapId=" + (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant())
    $block = $portalBlock.Match($text)
    if (-not $block.Success) { continue }
    foreach ($entry in $portalEntry.Matches($block.Groups[1].Value)) {
        $body = $entry.Groups[1].Value
        $tm = $targetMap.Match($body)
        if (-not $tm.Success) { continue }
        $to = [int]$tm.Groups[1].Value
        if ($to -eq 999999999 -or $to -eq $mapId) { continue }
        $scriptMatch = $script.Match($body)
        if ($scriptMatch.Success -and -not [string]::IsNullOrWhiteSpace($scriptMatch.Groups[1].Value)) { continue }
        if ((Get-Group $to) -ne $group) { continue }
        [void]$edgeKeys.Add(([long]$mapId -shl 32) -bor [long]$to)
    }
}

# Drop edges into maps WZ does not have: the router could never stand there.
$known = New-Object 'System.Collections.Generic.HashSet[int]'
foreach ($file in $files) { [void]$known.Add([int]($file.Name -replace '\.img\.xml$', '')) }
$edges = @($edgeKeys |
    ForEach-Object { [pscustomobject]@{ fromMapId = [int]($_ -shr 32); toMapId = [int]($_ -band 0xffffffffL) } } |
    Where-Object { $known.Contains($_.toMapId) } |
    Sort-Object fromMapId, toMapId)

$sha = [System.Security.Cryptography.SHA256]::Create()
$digest = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes(($sourceLines -join "`n")))
$sourceSha256 = -join ($digest | ForEach-Object { $_.ToString("x2") })

$catalog = [ordered]@{
    schemaVersion = 1
    catalogId = "world-standard-portal-route-graph-v1"
    sourcePath = $MapRoot.Replace('\', '/')
    sourceSha256 = $sourceSha256
    groups = $groups
    excludedRanges = $excludedRanges
    edges = $edges
}

$parent = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$json = $catalog | ConvertTo-Json -Depth 5
$fullOutputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
[System.IO.File]::WriteAllText($fullOutputPath, $json.Replace("`r`n", "`n") + "`n",
    (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Exported $($edges.Count) standard portal edges outside Victoria Island to $OutputPath"
