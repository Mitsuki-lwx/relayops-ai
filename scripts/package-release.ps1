param([string]$OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'artifacts/releases'))
$ErrorActionPreference = 'Stop'
$relayRoot = Split-Path -Parent $PSScriptRoot
$relayOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $relayOutput -Force | Out-Null
$relayBundle = Join-Path $relayOutput 'relayops-1.0.0'
if (Test-Path -LiteralPath $relayBundle) { throw 'Output bundle already exists. Use a new OutputDirectory to preserve previous artifacts.' }
Push-Location (Join-Path $relayRoot 'frontend')
try { & npm run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' } } finally { Pop-Location }
Push-Location (Join-Path $relayRoot 'backend')
try { & mvn -s ../ops/maven-settings.xml -gs ../ops/maven-settings.xml -B -ntp -Prelease -DskipTests clean package; if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed.' } } finally { Pop-Location }
New-Item -ItemType Directory -Path $relayBundle -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $relayRoot 'backend/target/relayops-backend-1.0.0.jar') -Destination (Join-Path $relayBundle 'relayops.jar')
$relayFiles = @('ops/compose.yml','ops/.env.example','ops/mysql-native.cnf.example','scripts/init-local.ps1','scripts/init-native-mysql.mjs','scripts/load-config.ps1','scripts/start-release.ps1','scripts/provision-user.ps1','scripts/verify-release.ps1','scripts/backup-database.mjs','scripts/mysql-tools.mjs','docs/operations.md','CHANGELOG.md')
foreach ($relayRelative in $relayFiles) {
    $relayTarget = Join-Path $relayBundle $relayRelative
    New-Item -ItemType Directory -Path (Split-Path -Parent $relayTarget) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $relayRoot $relayRelative) -Destination $relayTarget
}
Copy-Item -LiteralPath (Join-Path $relayRoot 'docs/release-guide.md') -Destination (Join-Path $relayBundle 'README.md')
$relayEntries = @(Get-ChildItem -LiteralPath $relayBundle -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
    @{path=[IO.Path]::GetRelativePath($relayBundle,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
@{version='1.0.0';files=$relayEntries} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $relayBundle 'manifest.json') -Encoding utf8NoBOM
& (Join-Path $PSScriptRoot 'verify-release.ps1') -Directory $relayBundle
$relayArchive = Join-Path $relayOutput 'relayops-1.0.0.zip'
[IO.Compression.ZipFile]::CreateFromDirectory($relayBundle,$relayArchive)
$relayHash = (Get-FileHash -LiteralPath $relayArchive -Algorithm SHA256).Hash.ToLowerInvariant()
"$relayHash  relayops-1.0.0.zip" | Set-Content -LiteralPath (Join-Path $relayOutput 'SHA256SUMS') -Encoding utf8NoBOM
Write-Output "Release bundle: $relayArchive"
