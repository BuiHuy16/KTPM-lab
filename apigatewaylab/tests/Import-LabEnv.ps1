# Dot-source this script: . .\tests\Import-LabEnv.ps1
$labEnvPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
if (!(Test-Path -LiteralPath $labEnvPath)) { throw 'Generate .env first with tests/New-LabEnv.ps1' }
foreach ($line in [System.IO.File]::ReadAllLines($labEnvPath)) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
