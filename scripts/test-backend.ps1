$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'load-config.ps1') -ConfigFile (Join-Path $taskRoot 'ops/.env')
$previousDatabaseTests = $env:RELAYOPS_DATABASE_TESTS
$previousMessagingTests = $env:RELAYOPS_MESSAGING_TESTS
$previousVhost = $env:RABBITMQ_VHOST
$previousDbUrl = $env:DB_URL
$previousModelMode = $env:RELAYOPS_MODEL_MODE
$previousRetention = $env:RELAYOPS_RETENTION_ENABLED
$env:RELAYOPS_MODEL_MODE = 'fake'
$env:RELAYOPS_RETENTION_ENABLED = 'false'
$env:RELAYOPS_DATABASE_TESTS = 'true'
$env:RELAYOPS_MESSAGING_TESTS = 'true'
$relayDatabaseBase = 'jdbc:mysql://127.0.0.1:13307'
if ($env:DB_URL) {
    if ($env:DB_URL -notmatch '^(jdbc:mysql://[^/]+)/') { throw 'Invalid configured JDBC base.' }
    $relayDatabaseBase = $Matches[1]
}
$env:DB_URL = "$relayDatabaseBase/relayops_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
$env:RELAYOPS_MESSAGING_DB_URL = "$relayDatabaseBase/relayops_messaging_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
$env:RELAYOPS_UPGRADE_DB_URL = "$relayDatabaseBase/relayops_upgrade_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
$env:RABBITMQ_VHOST = 'relayops-test'
Push-Location (Join-Path $taskRoot 'backend')
try {
    & mvn -s ../ops/maven-settings.xml -gs ../ops/maven-settings.xml -B -ntp test
    if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed.' }
} finally {
    $env:RELAYOPS_DATABASE_TESTS = $previousDatabaseTests
    $env:RELAYOPS_MESSAGING_TESTS = $previousMessagingTests
    $env:RABBITMQ_VHOST = $previousVhost
    $env:DB_URL = $previousDbUrl
    $env:RELAYOPS_MODEL_MODE = $previousModelMode
    $env:RELAYOPS_RETENTION_ENABLED = $previousRetention
    Pop-Location
}
