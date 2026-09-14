# 官方 FinRobot Equity 接入

默认 FinRobot 标签现在调用官方八个专题 Agent。Java 管理异步任务和 A 股证据，
Python 子进程加载固定版本的官方源码，通过 OpenAI Agents SDK 逐个执行。
没有用八个字段名包装同一次总体模型输出。

## 安装与运行

需要 JDK 21、Python 3.11+；当前依赖版本在 scripts/finrobot-requirements.txt。

```powershell
.\scripts\setup-finrobot.ps1 -Python 'C:\path\to\python.exe'
.\start.ps1
```

默认配置无需新增密钥。模型沿用被 Git 忽略的 config/application-local.yml 中
app.ai.models 与 finrobot-research 角色映射，支持页面选择模型。

```yaml
app:
  finrobot:
    engine: official
    python: tools/finrobot/.venv/Scripts/python.exe
    report-path: data/finrobot-reports
    task-timeout: 20m
    max-concurrent-tasks: 1
```

Linux 可创建同一路径虚拟环境，并将 python 改为 tools/finrobot/.venv/bin/python。
源码位于 third_party/finrobot，不依赖启动时联网克隆。默认分支变化不会改变运行版本。

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
