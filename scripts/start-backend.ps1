param([switch]$FakeModel, [ValidateRange(1024,65535)][int]$Port = 18080)
$ErrorActionPreference = 'Stop'
if ($Port -eq 8080) { throw 'Port 8080 is reserved; use 18080 or another port.' }
$env:PORT = $Port.ToString()
$taskRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $taskRoot 'ops/.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Run scripts/init-local.ps1 first.' }
. (Join-Path $PSScriptRoot 'load-config.ps1') -ConfigFile $envFile
Push-Location (Join-Path $taskRoot 'backend')
try {
    $mavenArguments = @('-s','../ops/maven-settings.xml','-gs','../ops/maven-settings.xml','-B','-ntp','spring-boot:run')
    if ($FakeModel) { $mavenArguments += '-Dspring-boot.run.arguments=--relayops.model.mode=fake' }
    & mvn.cmd @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Backend exited unsuccessfully.' }
} finally { Pop-Location }
