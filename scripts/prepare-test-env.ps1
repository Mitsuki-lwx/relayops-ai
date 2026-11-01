$ErrorActionPreference = 'Stop'
# Only create explicitly named test schemas and a test vhost; never reset the demo database.
$relayRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'load-config.ps1') -ConfigFile (Join-Path $relayRoot 'ops/.env')
Push-Location $relayRoot
try { node scripts/prepare-databases.mjs } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare isolated MySQL test schemas.' }
docker exec relayops-rabbitmq-1 rabbitmqctl add_vhost relayops-test
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare RabbitMQ test vhost.' }
docker exec relayops-rabbitmq-1 sh -c 'rabbitmqctl set_permissions -p relayops-test "$RABBITMQ_DEFAULT_USER" ".*" ".*" ".*"'
if ($LASTEXITCODE -ne 0) { throw 'Could not grant the local test user permissions.' }
Write-Output 'Isolated schemas and vhost ready.'
