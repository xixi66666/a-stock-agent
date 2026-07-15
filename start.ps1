$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$env:JAVA_HOME = & (Join-Path $projectRoot 'scripts\bootstrap-jdk.ps1') | Select-Object -Last 1
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:MAVEN_USER_HOME = Join-Path $projectRoot '.m2'
& (Join-Path $projectRoot 'mvnw.cmd') '-Dmaven.repo.local=.m2/repository' 'spring-boot:run'
exit $LASTEXITCODE
