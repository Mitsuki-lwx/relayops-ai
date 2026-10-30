$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
foreach ($line in [IO.File]::ReadAllLines((Join-Path $taskRoot 'ops/.env'))) {
    if ($line -match '^(DB_PASSWORD|MYSQL_ROOT_PASSWORD|RELAYOPS_DEMO_PASSWORD|RELAYOPS_MASTER_KEY|RABBITMQ_USERNAME|RABBITMQ_PASSWORD)=(.+)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
$previousDatabaseTests = $env:RELAYOPS_DATABASE_TESTS
$previousMessagingTests = $env:RELAYOPS_MESSAGING_TESTS
$previousVhost = $env:RABBITMQ_VHOST
$previousDbUrl = $env:DB_URL
$previousModelMode = $env:RELAYOPS_MODEL_MODE
$env:RELAYOPS_MODEL_MODE = 'fake'
$env:RELAYOPS_DATABASE_TESTS = 'true'
$env:RELAYOPS_MESSAGING_TESTS = 'true'
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3307/relayops_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:RABBITMQ_VHOST = 'relayops-test'
Push-Location (Join-Path $taskRoot 'backend')
try {
    & mvn.cmd -s ../ops/maven-settings.xml -gs ../ops/maven-settings.xml -B -ntp test
    if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed.' }
} finally {
    $env:RELAYOPS_DATABASE_TESTS = $previousDatabaseTests
    $env:RELAYOPS_MESSAGING_TESTS = $previousMessagingTests
    $env:RABBITMQ_VHOST = $previousVhost
    $env:DB_URL = $previousDbUrl
    $env:RELAYOPS_MODEL_MODE = $previousModelMode
    Pop-Location
}
