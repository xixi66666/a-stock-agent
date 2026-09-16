# UZI 投研模块（Python）

UZI 页面通过项目内固定的 `scripts/uzi-worker.py` 调用官方 `wbh604/UZI-Skill` 的
`run.py`，把官方产物归一化成 `uzi-bundle-v1`。官方仓库、虚拟环境和缓存都放在 Git
忽略的 `tools/uzi/` 下，不复制进本项目、不打包进 JAR，也不把 Python 命令、路径或
密钥暴露给页面。本文覆盖从零安装、配置、验证与排障。

## 前置条件

| 依赖 | 要求 | 自查 |
| --- | --- | --- |
| JDK | 21；`start.ps1` / `start.sh` 会自动准备仓库内工具链 | `.\mvnw.cmd -version` 显示 Java 21 |
| Python | 3.11 及以上 | `python --version` |
| Git | 首次安装需要 clone 官方仓库 | `git --version` |
| 网络 | 首次需要访问 GitHub 与 PyPI；运行任务需要访问官方脚本使用的行情源 | 见下文"网络受限环境" |
| 磁盘 | venv 与依赖、官方缓存 `tools/uzi/UZI-Skill/.cache`、报告目录 `data/uzi-reports` 均可写 | - |

UZI 不依赖本项目配置的模型：官方 CLI 直跑由规则引擎产出报告，`app.ai.models`
缺失时 UZI 仍可运行。页面选择的模型只作为非敏感提示通过环境变量
`UZI_MODEL_NAME` 传给官方脚本，密钥始终由操作者环境负责。

## Windows 安装

```powershell
.\scripts\setup-uzi.ps1
```

也可以用等价的批处理入口：

```bat
scripts\setup-uzi.cmd
```

脚本按顺序执行：

1. 依次探测 `py`、`python`、`python3`，找不到可用解释器时中止。
2. 若 `tools/uzi/UZI-Skill/run.py` 不存在，则执行
   `git clone --depth 1 --branch main https://github.com/wbh604/UZI-Skill.git`。
3. 创建虚拟环境 `tools/uzi/.venv`（已存在则复用）。
4. 用 venv 解释器安装官方 `requirements.txt`；同时设置 `PYTHONUTF8=1`，
   避免中文 Windows 按 GBK 读取含中文注释的 requirements 失败。
5. 打印建议写入 `config/application-local.yml` 的 `app.uzi.python` 路径。

参数：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `-UziRoot` | `tools\uzi\UZI-Skill` | 官方仓库放置目录 |
| `-Python` | 自动探测 | 用于创建 venv 的解释器；可传绝对路径 |
| `-RepoUrl` | `https://github.com/wbh604/UZI-Skill.git` | 官方仓库或镜像地址 |
| `-Ref` | `main` | clone 的分支或标签 |
| `-SkipInstall` | 关闭 | 仍会 clone（如缺失），但跳过建 venv 与依赖安装 |

官方 `requirements.txt` 还要求浏览器截图依赖。脚本不会自动执行，需要时手动安装
Chromium（否则部分截图/分享卡片能力缺失，其余维度仍会继续）：

```powershell
tools\uzi\.venv\Scripts\python.exe -m playwright install chromium
```

## Linux / macOS 安装

没有对应的 shell 脚本，手动执行等价步骤：

```bash
git clone --depth 1 --branch main https://github.com/wbh604/UZI-Skill.git tools/uzi/UZI-Skill
python3 -m venv tools/uzi/.venv
tools/uzi/.venv/bin/python -m pip install -r tools/uzi/UZI-Skill/requirements.txt
tools/uzi/.venv/bin/python -m playwright install chromium   # 可选
```

然后把 `app.uzi.python` 指向 `tools/uzi/.venv/bin/python`。

## 配置

首次启动前，把下面字段合并进 `config/application-local.yml` 已有的 `app` 节点，
不要重复创建同名 YAML 节点：

```yaml
app:
  uzi:
    root-path: tools/uzi/UZI-Skill
    python: tools/uzi/.venv/Scripts/python.exe   # Linux/macOS 用 tools/uzi/.venv/bin/python
    report-path: data/uzi-reports
    task-timeout: 30m
    max-concurrent-tasks: 1
```

| 字段 | 说明 |
| --- | --- |
| `root-path` | 官方仓库根目录，必须包含 `run.py`；默认 `tools/uzi/UZI-Skill` |
| `python` | 运行 worker 的解释器；建议按 setup 输出显式写出，避免落到系统 python |
| `report-path` | 受限输出目录，默认 `data/uzi-reports`（已被 Git 忽略） |
| `task-timeout` | 单个任务最长执行时间，默认 30 分钟 |
| `max-concurrent-tasks` | 并发上限 1-4，默认 1；超出返回 429 |

路径相对项目根目录解析。修改后需要重启应用；页面和 API 都不能覆盖这些路径或命令。

## 数据源与可选密钥

数据采集由官方仓库自己完成，本项目不传密钥。官方支持仓库根目录的 `.env`：

```powershell
Copy-Item tools\uzi\UZI-Skill\.env.example tools\uzi\UZI-Skill\.env
```

常用项：

- `MX_APIKEY=`：东财妙想 Skills Hub 免费 key，可提高行情与中文名纠错稳定性，
  海外或受限网络建议配置。
- `STOCK_NO_CACHE=1`、`UZI_NO_AUTO_OPEN=1`、`UZI_DISABLE_GLOBAL_PEERS=1` 等开关。

Java 子进程继承应用进程的环境变量，因此也可以用系统环境变量提供同样的值。
`tools/uzi/` 与 `config/application-local.yml` 都不会进入 Git。

### 网络受限环境

- 大陆网络：`pip` 超时可改用清华/阿里镜像重装依赖（官方脚本自身也会 fallback）。
- 海外或容器：东财 `push2.eastmoney.com` 等子域可能被限制，优先配置 `MX_APIKEY`。
- 数据源全部不可用时，官方脚本会保留 `dataGaps`，页面按缺口展示，不会估算填充。

## 启动与验证

1. 启动应用：`.\start.ps1`（Windows）或 `./start.sh`（Linux / macOS）。
2. 检查运行时状态：`GET http://localhost:10001/api/uzi/status`，返回 `enabled`、
   `installed`、`pythonAvailable`、`reason`、`rootPath`、`python`；
   `reason` 为 `READY`、`UZI_ROOT_NOT_FOUND` 或 `PYTHON_NOT_AVAILABLE`。
3. 页面验收：选择股票 → 打开"UZI 投研" → 选择深度（lite / medium / deep）和
   可选的 A-I 流派 → 运行任务。任务返回 202 后页面轮询
   `/api/uzi/tasks/{id}`，完成后渲染四个结构化维度并保留来源与缺口。

命令行烟测（不经过 Java，直接验证 Python 侧完整，会访问行情源）：

```powershell
tools\uzi\.venv\Scripts\python.exe scripts\uzi-worker.py `
  --uzi-root tools\uzi\UZI-Skill --code 600519 --depth lite `
  --output-dir data\uzi-reports\smoke
```

成功时退出码为 0，`data\uzi-reports\smoke\bundle.json` 包含 `schema`、`ticker`、
`structured`、`sources`、`dataGaps`。首次运行会产生官方缓存，占用随运行次数增长。

离线单测（不访问真实数据源，使用 `UZI_TEST_MODE`）：

```powershell
tools\uzi\.venv\Scripts\python.exe -m unittest scripts.tests.test_uzi_worker
```

## 接口

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/uzi/status` | GET | 安装与 Python 状态 |
| `/api/uzi/models` | GET | 安全模型目录（全局选择器共用） |
| `/api/uzi/tasks` | POST，JSON `{"code":"600519","depth":"medium","school":"F","modelId":"deepseek"}`；`school`、`modelId` 可省略 | 202 任务；无效输入 400；忙 429 |
| `/api/uzi/tasks/{id}` | GET | 任务进度与结果；不存在 404 |
| `/api/uzi/latest/{code}` | GET | 内存任务或磁盘最近成功报告 |

Task 包含 `status`（RUNNING / COMPLETED / FAILED）、`stage`、`depth`、`modelName`、
时间戳、`bundle`、`limitations`、`error`、`reportPath`。同一股票的进行中任务会复用；
最多保留 100 个内存任务；最新成功报告原子写入 `data/uzi-reports/<code>.json`。
对外错误使用固定消息，不返回路径、命令或异常堆栈。

## 排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 页面提示"UZI 未安装，请先运行 scripts/setup-uzi.ps1"；`reason=UZI_ROOT_NOT_FOUND` | `root-path` 下缺少 `run.py` | 重新运行 setup，或修正 `app.uzi.root-path` |
| "Python 不可用，请检查 app.uzi.python 配置"；`reason=PYTHON_NOT_AVAILABLE` | 配置的解释器不存在或不可执行 | 指向 `tools/uzi/.venv/Scripts/python.exe`（Linux 为 `bin/python`） |
| 手动 pip 安装报 Unicode/GBK 错误 | 中文 Windows 默认编码 | 先设置 `PYTHONUTF8=1` 再安装；setup 脚本已内置 |
| 任务失败，错误含"官方 UZI 返回非零状态" | 官方脚本失败：依赖缺失、数据源被限制或网络问题 | 用上面的命令行烟测复现；补装依赖、配置 `MX_APIKEY` |
| 任务失败，错误含"UZI bundle.json 不可用" | 官方脚本没有生成产物 | 检查磁盘权限与剩余空间；确认官方仓库版本未被本机改动 |
| 任务超时 | 深度过高或数据源过慢 | 先用 lite / medium；需要时提高 `app.uzi.task-timeout` |
| 提交任务返回 429 | 并发超过 `max-concurrent-tasks` | 等待现有任务完成 |
| 看不到官方脚本输出 | 设计如此：子进程 stdout/stderr 被丢弃 | 用命令行烟测直接观察；正式排查用 `taskId` 检索 `TASK_*` 日志（见 [点击、请求与投研任务日志](observability.md)） |

## 安全与边界

- Java 只用固定参数调用 worker；请求不能传入命令、脚本、根路径或环境变量。
- 输出目录必须位于 `report-path` 之下；`bundle.json` 必须是真实文件、非符号
  链接且小于 10 MB，证券代码必须与请求一致。
- 密钥只存在于操作者环境或官方仓库的 `.env`，不经过本项目 API，也不写日志。
- 官方脚本失败、依赖缺失和数据缺口都会作为任务错误或 `dataGaps` 呈现，
  不生成伪造结论；UZI 报告仍需人工复核，不构成投资建议。
