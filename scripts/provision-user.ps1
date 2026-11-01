param([Parameter(Mandatory)][string]$Username,
      [string]$CompanyName, [string]$TenantId,
      [string]$Jar = (Join-Path (Split-Path -Parent $PSScriptRoot) 'relayops.jar'),
      [string]$ConfigFile = (Join-Path (Split-Path -Parent $PSScriptRoot) 'ops/.env'))
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) { throw 'Release JAR missing.' }
if ([string]::IsNullOrWhiteSpace($CompanyName) -eq [string]::IsNullOrWhiteSpace($TenantId)) { throw 'Specify either CompanyName (new company) or TenantId (new member).' }
. (Join-Path $PSScriptRoot 'load-config.ps1') -ConfigFile $ConfigFile
$relayPreviousPassword = $env:RELAYOPS_PROVISION_PASSWORD
try {
    if (-not $env:RELAYOPS_PROVISION_PASSWORD) {
        $relaySecurePassword = Read-Host 'New account password (12 characters minimum, 72 UTF-8 bytes maximum)' -AsSecureString
        $env:RELAYOPS_PROVISION_PASSWORD = ConvertFrom-SecureString -SecureString $relaySecurePassword -AsPlainText
    }
    $relayArgs = @('-jar',$Jar,'--spring.profiles.active=release','--spring.main.web-application-type=none','--relayops.provision.enabled=true',"--relayops.provision.username=$Username",'--relayops.worker-enabled=false','--relayops.consumer-enabled=false','--relayops.relay-enabled=false','--relayops.recovery-enabled=false','--relayops.retention-enabled=false')
    if ($CompanyName) { $relayArgs += "--relayops.provision.company-name=$CompanyName" } else { $relayArgs += "--relayops.provision.tenant-id=$TenantId" }
    & java @relayArgs
    if ($LASTEXITCODE -ne 0) { throw 'Provisioning failed. Existing identities and passwords were not replaced.' }
} finally { $env:RELAYOPS_PROVISION_PASSWORD = $relayPreviousPassword }
