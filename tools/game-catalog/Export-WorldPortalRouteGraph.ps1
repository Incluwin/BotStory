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
# The Haunted House 682000000 is part of Masteria: the NLC taxi lands there, and its right portal is the only
# way into the Phantom Forest (Crimsonwood). Only that one map joins the group, not the mansion behind it.
# Amoria is two ranges: the village and its road (6800xxxxx up to 680010100), then Purplewood Forest and Amos'
# Training Ground (670000100-670010000). The Amorian Challenge's own maps from 670010100 on stay out: only Amos the
# Strong's ticket warp leads in.

$ErrorActionPreference = "Stop"

$groups = @(
    [ordered]@{ id = "ereve"; firstMapId = 130000000; endMapId = 140000000 },
    [ordered]@{ id = "rien"; firstMapId = 140000000; endMapId = 150000000 },
    [ordered]@{ id = "ossyria"; firstMapId = 200000000; endMapId = 310000000 },
    [ordered]@{ id = "singapore"; firstMapId = 540000000; endMapId = 550000000 },
    [ordered]@{ id = "malaysia"; firstMapId = 550000000; endMapId = 560000000 },
    [ordered]@{ id = "masteria"; firstMapId = 600000000; endMapId = 620000000 },
    [ordered]@{ id = "masteria"; firstMapId = 682000000; endMapId = 682000001 },
    [ordered]@{ id = "amoria"; firstMapId = 670000000; endMapId = 670010001 },
    [ordered]@{ id = "amoria"; firstMapId = 680000000; endMapId = 680100000 },
    [ordered]@{ id = "zipangu"; firstMapId = 800000000; endMapId = 810000000 }
)
$excludedRanges = @(
    [ordered]@{ firstMapId = 200090000; endMapId = 200090500; reason = "ferry, crane and boat ride maps" },
    [ordered]@{ firstMapId = 200090520; endMapId = 200100000; reason = "ferry, crane and boat ride maps" }
)

# Scripted portals routes may walk through: the script always sends the traveller to destinationMapId (or does so when
# the gate holds: one of completedAny completed, or one of activeAny started or completed, as the script checks). The
# portal id is looked up by name in WZ; AgentWorldPortalRouteGraphFreshnessTest checks each against WZ and the script.
# The dragon flights 200090500/200090510 (fly=1) stay in the graph between the two ride ranges above: Leafre Station's
# Halfling (2082003, a travel leg) sets the rider down on 200090500, and it flies east through 200090510 into the
# Temple of Time; the way back is the Temple's out00, 200090500's minar00 and Leafre Station's own exit west00.
$scriptedPortals = @(
    [ordered]@{ sourceMapId = 100000002; destinationMapId = 106020000; portalName = "TD00"; script = "TD_MC_first";
        gate = [ordered]@{ completedAny = @(2260); activeAny = @(2300..2310) } },
    [ordered]@{ sourceMapId = 222020400; destinationMapId = 300000100; portalName = "in01"; script = "move_elin" },
    [ordered]@{ sourceMapId = 200090510; destinationMapId = 270000100; portalName = "in00"; script = "templeenter" },
    [ordered]@{ sourceMapId = 270000100; destinationMapId = 200090510; portalName = "out00"; script = "outTemple" },
    [ordered]@{ sourceMapId = 200090500; destinationMapId = 240000110; portalName = "minar00"; script = "undodraco" },
    [ordered]@{ sourceMapId = 240000110; destinationMapId = 240000100; portalName = "west00"; script = "dracoout" }
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

# Each scripted portal's id, from its name on the source map; the portal must carry the script named above.
$portalName = [regex]'<string name="pn" value="([^"]*)"/>'
$scripted = @(foreach ($portal in $scriptedPortals) {
    $source = $portal.sourceMapId
    $file = Join-Path $MapRoot ("Map" + [math]::Floor($source / 100000000)) | Join-Path -ChildPath ("{0:D9}.img.xml" -f $source)
    $block = $portalBlock.Match([System.IO.File]::ReadAllText($file))
    $found = $null
    foreach ($entry in [regex]::Matches($block.Groups[1].Value, '(?s)<imgdir name="(\d+)">(.*?)</imgdir>')) {
        if ($portalName.Match($entry.Groups[2].Value).Groups[1].Value -eq $portal.portalName) { $found = $entry; break }
    }
    if ($null -eq $found) { throw "no portal $($portal.portalName) on map $source" }
    if ($script.Match($found.Groups[2].Value).Groups[1].Value -ne $portal.script) {
        throw "portal $($portal.portalName) on map $source does not run $($portal.script)"
    }
    $out = [ordered]@{
        sourceMapId = $source
        destinationMapId = $portal.destinationMapId
        portalName = $portal.portalName
        portalId = [int]$found.Groups[1].Value
        script = $portal.script
    }
    if ($portal.Contains("gate")) { $out.gate = $portal.gate }
    $out
})

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
    scriptedPortals = $scripted
    edges = $edges
}

$parent = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$json = $catalog | ConvertTo-Json -Depth 5
$fullOutputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
[System.IO.File]::WriteAllText($fullOutputPath, $json.Replace("`r`n", "`n") + "`n",
    (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Exported $($edges.Count) standard portal edges outside Victoria Island and $($scripted.Count) scripted portals to $OutputPath"
