[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $projectRoot '.tools'
$jdkDir = Join-Path $toolsDir 'jdk-21'
$javaExe = Join-Path $jdkDir 'bin\java.exe'

if (Test-Path -LiteralPath $javaExe) {
    Write-Output $jdkDir
    exit 0
}

$properties = @{}
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'toolchain.properties') | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') {
        $properties[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$url = $properties['windows.x64.url']
$expectedHash = $properties['windows.x64.sha256']
$downloadsDir = Join-Path $toolsDir 'downloads'
$archive = Join-Path $downloadsDir 'temurin-jdk-21-windows-x64.zip'
$extractDir = Join-Path $toolsDir 'jdk-extract'
New-Item -ItemType Directory -Force -Path $downloadsDir | Out-Null

if (-not (Test-Path -LiteralPath $archive)) {
    Write-Host "Downloading Eclipse Temurin $($properties['jdk.version'])..."
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $archive
}

$actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne $expectedHash) {
    throw "JDK checksum mismatch. Expected $expectedHash but received $actualHash."
}

if (Test-Path -LiteralPath $extractDir) {
    Remove-Item -LiteralPath $extractDir -Recurse -Force
}
New-Item -ItemType Directory -Path $extractDir | Out-Null
Expand-Archive -LiteralPath $archive -DestinationPath $extractDir
$extractedJdk = Get-ChildItem -LiteralPath $extractDir -Directory | Select-Object -First 1
if ($null -eq $extractedJdk -or -not (Test-Path -LiteralPath (Join-Path $extractedJdk.FullName 'bin\java.exe'))) {
    throw 'Downloaded archive does not contain a valid JDK.'
}
Move-Item -LiteralPath $extractedJdk.FullName -Destination $jdkDir
Remove-Item -LiteralPath $extractDir -Recurse -Force
Write-Output $jdkDir
