[CmdletBinding()]
param(
    [string]$UziRoot = "tools\uzi\UZI-Skill",
    [string]$Python = "",
    [string]$RepoUrl = "https://github.com/wbh604/UZI-Skill.git",
    [string]$Ref = "main",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$resolvedUziRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot $UziRoot))
$venvRoot = Join-Path (Split-Path $resolvedUziRoot -Parent) ".venv"

function Invoke-Checked([string]$FilePath, [string[]]$Arguments) {
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "命令执行失败: $FilePath"
    }
}

function Resolve-PythonCommand {
    if ($Python) {
        if (Test-Path -LiteralPath $Python) { return (Resolve-Path -LiteralPath $Python).Path }
        $candidate = Get-Command $Python -ErrorAction SilentlyContinue
        if ($candidate) { return $candidate.Source }
        throw "找不到指定 Python: $Python"
    }
    foreach ($name in @("py", "python", "python3")) {
        $candidate = Get-Command $name -ErrorAction SilentlyContinue
        if ($candidate) {
            try {
                & $candidate.Source --version *> $null
                if ($LASTEXITCODE -eq 0) { return $candidate.Source }
            } catch { }
        }
    }
    throw "未找到 Python 3。请安装 Python 3.11+ 后重试。"
}

$pythonCommand = Resolve-PythonCommand
if (-not (Test-Path -LiteralPath (Join-Path $resolvedUziRoot "run.py"))) {
    New-Item -ItemType Directory -Force -Path (Split-Path $resolvedUziRoot -Parent) | Out-Null
    Invoke-Checked "git" @("clone", "--depth", "1", "--branch", $Ref, $RepoUrl, $resolvedUziRoot)
}

$runScript = Join-Path $resolvedUziRoot "run.py"
if (-not (Test-Path -LiteralPath $runScript -PathType Leaf)) {
    throw "UZI 仓库不完整，缺少 run.py: $resolvedUziRoot"
}

if (-not $SkipInstall) {
    # 中文 Windows 的 pip 默认按 GBK 读取含中文注释的 requirements.txt，先切到 UTF-8 模式。
    $env:PYTHONUTF8 = "1"
    if (-not (Test-Path -LiteralPath $venvRoot)) {
        Invoke-Checked $pythonCommand @("-m", "venv", $venvRoot)
    }
    $venvPython = Join-Path $venvRoot "Scripts\python.exe"
    if (-not (Test-Path -LiteralPath $venvPython)) {
        throw "Python 虚拟环境创建失败: $venvPython"
    }
    $requirements = Join-Path $resolvedUziRoot "requirements.txt"
    if (Test-Path -LiteralPath $requirements -PathType Leaf) {
        Invoke-Checked $venvPython @("-m", "pip", "install", "-r", $requirements)
    } else {
        Write-Warning "UZI 仓库没有 requirements.txt，将跳过依赖安装。"
    }
}

$worker = Join-Path $projectRoot "scripts\uzi-worker.py"
if (-not (Test-Path -LiteralPath $worker -PathType Leaf)) {
    throw "项目 Worker 不存在: $worker"
}

$configuredPython = Join-Path $venvRoot "Scripts\python.exe"
if (-not (Test-Path -LiteralPath $configuredPython)) { $configuredPython = $pythonCommand }
if ([IO.Path]::IsPathRooted($configuredPython)) {
    $configuredPython = [IO.Path]::GetRelativePath($projectRoot, $configuredPython)
}

Write-Host "UZI 已准备: $resolvedUziRoot"
Write-Host "建议在 config/application-local.yml 中配置:"
Write-Host "app:"
Write-Host "  uzi:"
Write-Host "    root-path: $UziRoot"
Write-Host "    python: $configuredPython"
Write-Host "如果使用 UZI 的模型能力，请仅在 config/application-local.yml 或系统环境变量中配置密钥。"
