[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = & (Join-Path $PSScriptRoot 'bootstrap-jdk.ps1') | Select-Object -Last 1
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:MAVEN_USER_HOME = Join-Path $projectRoot '.m2'

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'mvnw.cmd') '-Dmaven.repo.local=.m2/repository' '-Pexternal' 'test'
    if ($LASTEXITCODE -ne 0) {
        throw "Live data verification failed with exit code $LASTEXITCODE."
    }
    $latest = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'target\data-verification') -Filter 'verification-*.md' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $latest) {
        throw 'Live data verification completed without producing a report.'
    }
    Write-Output "Verification report: $($latest.FullName)"
} finally {
    Pop-Location
}
