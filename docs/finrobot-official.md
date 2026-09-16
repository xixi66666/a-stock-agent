# 官方 FinRobot Equity 接入

默认 FinRobot 标签现在调用官方八个专题 Agent。Java 管理异步任务和 A 股证据，
Python 子进程加载固定版本的官方源码，通过 OpenAI Agents SDK 逐个执行。
没有用八个字段名包装同一次总体模型输出。

## 前置条件

| 依赖 | 要求 | 自查 |
| --- | --- | --- |
| JDK | 21，由仓库内工具链提供 | `.\mvnw.cmd -version` 显示 Java 21 |
| Python | 3.11 及以上；依赖固定版本见 `scripts/finrobot-requirements.txt`（openai-agents 0.22.2、openai 3.13.0、pandas 3.0.1、numpy 2.3.5、pydantic 2.13.5） | `python --version` |
| 模型 | 至少一个已配置的 OpenAI 兼容 Chat Completions 模型，且 `finrobot-research` 角色指向它 | `GET /api/finrobot/runtime`、`GET /api/ai/models` |
| 网络 | 安装时需要 PyPI；运行任务需要行情/财报数据源与所选模型 | - |
| 磁盘 | `tools/finrobot/.venv` 与 `data/finrobot-reports` 可写 | - |

官方源码已随仓库提交在 `third_party/finrobot`（含 `UPSTREAM.json` 哈希清单），
安装与运行都不需要联网 clone 上游仓库。

## 安装与运行

```powershell
.\scripts\setup-finrobot.ps1 -Python 'C:\path\to\python.exe'
.\start.ps1
```

`-Python` 用于指定创建 venv 的解释器，不传时使用 PATH 中的 `python`。脚本按顺序
执行：校验解释器版本 → 创建或复用 `tools/finrobot/.venv` → 安装
`scripts/finrobot-requirements.txt` → 运行
`python scripts/finrobot_worker.py --check` 校验源码哈希与依赖导入；任何一步失败
都会中止并报错。

Linux / macOS 没有对应脚本，手动执行等价步骤：

```bash
python3 -m venv tools/finrobot/.venv
tools/finrobot/.venv/bin/python -m pip install -r scripts/finrobot-requirements.txt
tools/finrobot/.venv/bin/python scripts/finrobot_worker.py --check
```

默认配置无需新增密钥。模型沿用被 Git 忽略的 config/application-local.yml 中
app.ai.models 与 finrobot-research 角色映射，支持页面选择模型。未显式配置
`app.finrobot.python` 时使用代码默认值；Linux / macOS 需要改成
`tools/finrobot/.venv/bin/python`。

```yaml
app:
  finrobot:
    engine: official
    python: tools/finrobot/.venv/Scripts/python.exe
    report-path: data/finrobot-reports
    task-timeout: 20m
    max-concurrent-tasks: 1
```

官方源码位于 third_party/finrobot，不依赖启动时联网克隆；默认分支变化不会改变
运行版本。`engine: legacy` 可临时切回旧同步链路，见"迁移与验证"。

## REST 契约

| 方法 | 地址 | 输入与结果 |
|---|---|---|
| GET | /api/finrobot/runtime | 引擎及安装状态，无凭据 |
| GET | /api/finrobot/models | 已有安全模型目录 |
| POST | /api/finrobot/tasks | JSON `{ "code": "600519", "modelId": "deepseek" }`，返回 202 和 Task |
| GET | /api/finrobot/tasks/{id} | 查询 Task |
| GET | /api/finrobot/latest/{code} | 本次服务运行中的最近任务；没有则 204 |
| POST | /api/finrobot/tasks/{id}/cancel | 取消任务 |
| GET | /api/finrobot/tasks/{id}/artifacts/{kind} | html / json / evidence 附件 |

Task 包含 id、code、modelName、status、stage、completed、startedAt、updatedAt、report、error。
状态为 RUNNING / COMPLETED / PARTIAL / FAILED / CANCELLED。
专题失败仅标记该专题；全部失败时不返回成功报告。无模型配置不伪造模板报告。
非法代码或模型返回 400，不存在任务返回 404，未完成下载返回 409，超过并发上限返回 429。
超时或取消后迟到的结果不得覆盖终态；切换标签停止页面轮询，不自动取消后端任务。

## 复用与适配

直接复用八个官方专题 Agent 的提示词、输出类型以及官方历史财务指标提取函数。
传输层使用 JSON Object 模式兼容现有模型，SDK 继续验证官方 Pydantic 输出结构。
密钥只经子进程 stdin 进入内存；SDK tracing 关闭，stdout/stderr 不写日志。

证据包保留完整快照和财报 DataSection，包含状态、时间、缓存与来源。年度计算只取
12 月 31 日报表；零值保留，缺失不补零，不将营业利润或经营现金流冒充 EBITDA/FCF。
对上游 truthiness 导致的零值丢失，在适配输出时修正。季度报表保留为原始证据，
不混入年度计算。计算结果以人民币元标注。

所有模型章节标记 UNVERIFIED，并提示缺失或不匹配的来源编号；这不是事实核验完成。
来源从 Java 证据生成，不从模型文本伪造。HTML 全部转义，下载附带 sandbox CSP。
结果与证据保存在 Git 忽略的 data/finrobot-reports。当前任务索引保存在内存，重启后
不能通过旧任务 URL 下载；磁盘产物保留，可手动查看。目录需按使用量定期归档。

## 有意保留的限制

上游估值引擎存在固定倍数、净债务占比和 EBITDA 转 FCF 的默认假设，本项目未启用。
当前标准财务字段不齐全且没有明确预测假设，因此预测、DCF 等标记 UNAVAILABLE。
报告使用本项目人民币 HTML 导出，未套用官方美元 HTML/PDF 模板。
未接入未开源 V2、Desktop 产品或自动外网研究工具，也没有宣称复刻这些功能。

## 排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `/api/finrobot/runtime` 返回 `installed: false`，message 提示运行 setup | `app.finrobot.python` 指向的解释器或 `third_party/finrobot/UPSTREAM.json` 不存在 | 重新运行 `.\scripts\setup-finrobot.ps1`；确认 `python` 路径存在且从项目根目录启动 |
| 提交任务返回 400（模型不可用） | `app.ai.models` 没有可用模型，或 `finrobot-research` 角色未映射 | 补全模型配置并重启；在全局模型选择器中选择可用模型 |
| 任务失败，错误"当前模型接口不支持 Chat Completions" | 模型 `completions-path` 不是 OpenAI Chat Completions 形态 | 换用兼容该接口的模型 |
| 任务失败，错误"官方引擎执行失败，请检查 Python 环境、模型连接和数据状态" | 子进程异常：venv 依赖缺失、模型不可达或数据源失败 | 先用 `--check` 验证 Python 侧；再看模型连通性探测与数据源状态 |
| 任务失败，错误"报告契约不匹配" | bundle 的 `ticker`、`schema` 或 `upstreamCommit` 不符 | 确认 `third_party/finrobot` 未被本机修改；按 `UPSTREAM.json` 重新校验 |
| 报告为 PARTIAL | 部分专题失败，失败项单独标记 | 不代表核验通过；结合失败专题重试或更换模型 |
| 任务超时 | 专题多、模型慢 | 提高 `app.finrobot.task-timeout` 或更换模型 |
| 提交任务返回 429 | 并发超过 `app.finrobot.max-concurrent-tasks` | 等待现有任务完成 |
| 重启后旧任务 URL 返回 404 | 任务索引只在内存中，不落盘 | 重启后到 `data/finrobot-reports` 手动查看已落盘产物 |

子进程 stdout/stderr 被丢弃是设计选择。正式排查用 `taskId` 检索 `TASK_*` 日志，
见 [点击、请求与投研任务日志](observability.md)。

## 迁移与验证

旧同步 POST /api/finrobot/research 保留，用 app.finrobot.engine: legacy 可临时切回旧页面。
默认官方流程失败不会暗中回退旧报告。新旧两套 UI 分别测试，待真实报告质量验收后再移除旧实现。

```powershell
& tools/finrobot/.venv/Scripts/python.exe -m unittest scripts.tests.test_finrobot_worker
# Maven 前按 AGENTS.md 设置 JAVA_HOME 为 .tools/jdk-21
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' test
npm.cmd run test:ui
```

Python 测试包括零值/缺失/来源保留、八个原始 Agent 的独立执行与本地 HTTP 模型协议。
默认测试不访问真实模型或行情。真实供应商验证必须单独执行并明确报告验证范围。
