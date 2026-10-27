[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) {
    throw '.env already exists. Existing credentials are preserved.'
}
function New-Secret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}
$lines = @(
    "JWT_SECRET=$(New-Secret)"
    "INTERNAL_API_TOKEN=$(New-Secret)"
    "POSTGRES_PASSWORD=$(New-Secret)"
    "USER_DB_PASSWORD=$(New-Secret)"
    "ORDER_DB_PASSWORD=$(New-Secret)"
    "REDIS_PASSWORD=$(New-Secret)"
    "MINIO_ROOT_USER=lab_$((New-Secret).Substring(0, 16))"
    "MINIO_ROOT_PASSWORD=$(New-Secret)"
    'MINIO_PUBLIC_ENDPOINT=http://localhost:9000'
    'MINIO_BUCKET=avatars'
)
[System.IO.File]::WriteAllLines($envPath, $lines, (New-Object System.Text.UTF8Encoding $false))
Write-Host 'Generated local .env without printing secrets.'
