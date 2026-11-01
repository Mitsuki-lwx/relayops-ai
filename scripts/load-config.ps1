param([Parameter(Mandatory)][string]$ConfigFile)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ConfigFile -PathType Leaf)) { throw 'Configuration file missing. Run init-local.ps1 and configure ops/.env.' }
$relayAllowed = @('DB_URL','DB_USERNAME','DB_PASSWORD','MYSQL_ROOT_PASSWORD','RELAYOPS_MASTER_KEY','RELAYOPS_DEMO_PASSWORD','RABBITMQ_HOST','RABBITMQ_PORT','RABBITMQ_USERNAME','RABBITMQ_PASSWORD','RABBITMQ_VHOST','RELAYOPS_MODEL_MODE','RELAYOPS_MODEL_ENDPOINT','RELAYOPS_MODEL_API_KEY','RELAYOPS_MODEL_NAME','RELAYOPS_MODEL_TIMEOUT_SECONDS','RELAYOPS_MODEL_MAX_TOKENS','RELAYOPS_MODEL_TOKEN_PARAMETER','RELAYOPS_MODEL_JSON_MODE','RELAYOPS_RETENTION_ENABLED')
foreach ($relayLine in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $ConfigFile))) {
    if ($relayLine -match '^([A-Z][A-Z0-9_]*)=(.*)$' -and $Matches[1] -in $relayAllowed) {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
