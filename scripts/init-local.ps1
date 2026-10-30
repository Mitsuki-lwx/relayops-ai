$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $taskRoot 'ops/.env'
function New-LocalSecret {
    return [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
}
$existing = if (Test-Path -LiteralPath $envFile) { [IO.File]::ReadAllLines($envFile) } else { @() }
$lines = [Collections.Generic.List[string]]::new()
foreach ($name in @('DB_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'RELAYOPS_DEMO_PASSWORD', 'RELAYOPS_MASTER_KEY', 'RABBITMQ_PASSWORD')) {
    if (-not ($existing | Where-Object { $_.StartsWith($name + '=') })) { $lines.Add($name + '=' + (New-LocalSecret)) }
}
if (-not ($existing | Where-Object { $_.StartsWith('RABBITMQ_USERNAME=') })) { $lines.Add('RABBITMQ_USERNAME=relayops') }
if ($lines.Count -gt 0) {
    # Include a separator in case a manually edited file has no trailing newline.
    if (Test-Path -LiteralPath $envFile) { [IO.File]::AppendAllText($envFile, [Environment]::NewLine) }
    [IO.File]::AppendAllLines($envFile, $lines)
}
Write-Output "Local configuration ready; added $($lines.Count) missing entries. Existing passwords and encryption key preserved."
