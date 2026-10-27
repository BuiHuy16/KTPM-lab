[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
# Keep wrapper downloads and Maven artifacts under this project.
$env:MAVEN_USER_HOME = Join-Path $projectRoot '.build/maven-user'
Push-Location $projectRoot
try {
    foreach ($pom in @('pom.xml', 'user-service/pom.xml', 'order-service/pom.xml')) {
        & .\mvnw.cmd -B -ntp -f $pom verify
        if ($LASTEXITCODE -ne 0) { throw "Build failed: $pom" }
    }
} finally { Pop-Location }
