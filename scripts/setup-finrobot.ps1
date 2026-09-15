[CmdletBinding()]
param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $project
try {
    & $Python -c "import sys; assert sys.version_info >= (3, 11), 'Python 3.11+ required'"
    if ($LASTEXITCODE -ne 0) { throw '请使用 -Python 指定 Python 3.11 或更新版本' }
    $venv = Join-Path $project 'tools/finrobot/.venv'
    $executable = Join-Path $venv 'Scripts/python.exe'
    if (-not (Test-Path -LiteralPath $executable)) {
        & $Python -m venv $venv
        if ($LASTEXITCODE -ne 0) { throw '创建 FinRobot 虚拟环境失败' }
    }
    & $executable -m pip install -r (Join-Path $PSScriptRoot 'finrobot-requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw '安装 FinRobot 依赖失败' }
    & $executable (Join-Path $PSScriptRoot 'finrobot_worker.py') --check
    if ($LASTEXITCODE -ne 0) { throw '官方源码或 Python 环境验证失败' }
    Write-Host '官方 FinRobot Equity 已准备；模型沿用 config/application-local.yml 的 app.ai.models 配置。'
} finally { Pop-Location }
