param([string]$Directory = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$relayRoot = (Resolve-Path -LiteralPath $Directory).Path
$relayManifest = Get-Content -LiteralPath (Join-Path $relayRoot 'manifest.json') -Raw | ConvertFrom-Json
if ($relayManifest.version -ne '1.0.0') { throw 'Unexpected release version.' }
$relayActual = @(Get-ChildItem -LiteralPath $relayRoot -File -Recurse -Force | ForEach-Object { [IO.Path]::GetRelativePath($relayRoot,$_.FullName).Replace('\','/') } | Where-Object { $_ -ne 'manifest.json' })
if (Compare-Object -ReferenceObject @($relayManifest.files.path) -DifferenceObject $relayActual -CaseSensitive) { throw 'Unlisted or missing distribution files.' }
foreach ($relayEntry in $relayManifest.files) {
    $relayFile = [IO.Path]::GetFullPath((Join-Path $relayRoot $relayEntry.path))
    if (-not $relayFile.StartsWith($relayRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid manifest path.' }
    if ((Get-FileHash -LiteralPath $relayFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $relayEntry.sha256) { throw "Checksum mismatch: $($relayEntry.path)" }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$relayZip = [IO.Compression.ZipFile]::OpenRead((Join-Path $relayRoot 'relayops.jar'))
try {
    foreach ($relayRequired in @('BOOT-INF/classes/static/index.html','BOOT-INF/classes/static/fonts/OFL.txt','BOOT-INF/classes/application-release.yml')) {
        if (-not $relayZip.GetEntry($relayRequired)) { throw "Missing JAR resource: $relayRequired" }
    }
} finally { $relayZip.Dispose() }
Write-Output "Verified RelayOps $($relayManifest.version): $($relayManifest.files.Count) files."
