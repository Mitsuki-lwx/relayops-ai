param([string]$Jar = (Join-Path (Split-Path -Parent $PSScriptRoot) 'backend/target/relayops-backend-1.0.0.jar'),
      [string]$Channel = 'msedge', [ValidateRange(1024,65535)][int]$Port = 18081,
      [ValidateRange(0,65535)][int]$DatabasePort = 0, [switch]$PackagedOnly)
$ErrorActionPreference = 'Stop'
if ($Port -eq 8080 -or $Port -eq 18080) { throw 'Browser tests must use a dedicated port.' }
$relayRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath $Jar)) { throw 'Package the release JAR first.' }
. (Join-Path $PSScriptRoot 'load-config.ps1') -ConfigFile (Join-Path $relayRoot 'ops/.env')
if ($DatabasePort -eq 0) {
    $DatabasePort = 13307
    if ($env:DB_URL -match '^jdbc:mysql://[^/:]+:([0-9]+)/') { $DatabasePort = [int]$Matches[1] }
}
$relayDatabase = 'relayops_e2e_' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$env:RELAYOPS_E2E_DATABASE = $relayDatabase
if (-not $env:DB_USERNAME) { $env:DB_USERNAME = 'relayops' }
Push-Location $relayRoot
try { & node scripts/prepare-e2e.mjs $relayDatabase; if ($LASTEXITCODE -ne 0) { throw 'Isolated browser database preparation failed.' } } finally { Pop-Location }
$env:DB_URL = "jdbc:mysql://127.0.0.1:$DatabasePort/$($relayDatabase)?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
$env:RABBITMQ_VHOST = 'relayops-test'
$env:RELAYOPS_MODEL_MODE = 'fake'
$env:RELAYOPS_MASTER_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:RELAYOPS_RETENTION_ENABLED = 'true'
$env:RELAYOPS_MODEL_API_KEY = ''
$env:RELAYOPS_MODEL_ENDPOINT = ''
$env:RELAYOPS_MODEL_NAME = ''
$relayArtifact = Join-Path $relayRoot 'artifacts/e2e'
New-Item -ItemType Directory -Path $relayArtifact -Force | Out-Null
$relayConfig = Join-Path $relayArtifact 'config.env'
$relayNames = @('DB_URL','DB_USERNAME','DB_PASSWORD','RELAYOPS_MASTER_KEY','RELAYOPS_DEMO_PASSWORD','RABBITMQ_USERNAME','RABBITMQ_PASSWORD','RABBITMQ_VHOST','RELAYOPS_MODEL_MODE')
[IO.File]::WriteAllLines($relayConfig,@($relayNames | ForEach-Object { $_ + '=' + [Environment]::GetEnvironmentVariable($_) }),[Text.UTF8Encoding]::new($false))
$env:RELAYOPS_PROVISION_PASSWORD = $env:RELAYOPS_DEMO_PASSWORD
& (Join-Path $PSScriptRoot 'provision-user.ps1') -Username release.owner -CompanyName '发布验收公司' -Jar $Jar -ConfigFile $relayConfig *> (Join-Path $relayArtifact 'provision-owner.log')
$relayProvisioned = Select-String -LiteralPath (Join-Path $relayArtifact 'provision-owner.log') -Pattern 'RELAYOPS_PROVISIONED userId=\S+ tenantId=(\S+)'
if (-not $relayProvisioned) { throw 'Owner provisioning did not complete.' }
$relayTenant = $relayProvisioned.Matches[0].Groups[1].Value
& (Join-Path $PSScriptRoot 'provision-user.ps1') -Username release.member -TenantId $relayTenant -Jar $Jar -ConfigFile $relayConfig *> (Join-Path $relayArtifact 'provision-member.log')
$env:RELAYOPS_PROVISION_PASSWORD = ''
$relayStart = @{ FilePath='java'; ArgumentList=@('-jar',$Jar,'--spring.profiles.active=release',"--server.port=$Port"); WorkingDirectory=$relayRoot; PassThru=$true; RedirectStandardOutput=(Join-Path $relayArtifact 'backend.log'); RedirectStandardError=(Join-Path $relayArtifact 'backend-error.log') }
if ($IsWindows) { $relayStart.WindowStyle = 'Hidden' }
$relayProcess = Start-Process @relayStart
try {
    function Wait-RelayBackend {
        for ($relayTry=0; $relayTry -lt 60; $relayTry++) {
            if ($relayProcess.HasExited) { throw 'Isolated backend exited; inspect artifacts/e2e/backend-error.log.' }
            try { if ((Invoke-WebRequest "http://127.0.0.1:$Port/healthz" -TimeoutSec 2).StatusCode -eq 200) { return } } catch {}
            Start-Sleep -Milliseconds 500
        }
        throw 'Isolated backend did not become ready.'
    }
    Wait-RelayBackend
    $env:RELAYOPS_BROWSER_BASE = "http://127.0.0.1:$Port"
    $env:RELAYOPS_BROWSER_CHANNEL = $Channel
    $env:RELAYOPS_E2E_CONFIG = $relayConfig
    Push-Location $relayRoot
    try { & node scripts/verify-packaged.mjs; if ($LASTEXITCODE -ne 0) { throw 'Packaged release acceptance failed.' } } finally { Pop-Location }
    if ($PackagedOnly) { return }
    Stop-Process -Id $relayProcess.Id
    $relayProcess.WaitForExit()
    # Legacy interaction suites use seeded demo principals, only in this new test schema.
    $relayStart.ArgumentList = @('-jar',$Jar,'--spring.profiles.active=local',"--server.port=$Port")
    $relayProcess = Start-Process @relayStart
    Wait-RelayBackend
    Push-Location (Join-Path $relayRoot 'frontend')
    try { & npx playwright test; if ($LASTEXITCODE -ne 0) { throw 'Browser acceptance failed.' } } finally { Pop-Location }
} finally { if (-not $relayProcess.HasExited) { Stop-Process -Id $relayProcess.Id } }
