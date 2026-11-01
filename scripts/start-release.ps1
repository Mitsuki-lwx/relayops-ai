param([string]$Jar = (Join-Path (Split-Path -Parent $PSScriptRoot) 'relayops.jar'),
      [string]$ConfigFile = (Join-Path (Split-Path -Parent $PSScriptRoot) 'ops/.env'),
      [ValidateRange(1024,65535)][int]$Port = 18080)
$ErrorActionPreference = 'Stop'
if ($Port -eq 8080) { throw 'Port 8080 is reserved; use 18080.' }
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) { throw 'Release JAR missing. Build or extract the release bundle first.' }
. (Join-Path $PSScriptRoot 'load-config.ps1') -ConfigFile $ConfigFile
$env:PORT = $Port.ToString()
& java -jar $Jar --spring.profiles.active=release
if ($LASTEXITCODE -ne 0) { throw 'Release application exited unsuccessfully. Inspect the local log without sharing credentials.' }
