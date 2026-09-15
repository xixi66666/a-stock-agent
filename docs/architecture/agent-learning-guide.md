# A 股研究 Agent 学习总览

这份文档是源码的阅读地图。建议先读“主链路”，再按兴趣进入 Provider、确定性分析、总体报告或学习型 Agent 支线。项目刻意把“事实获取”“指标计算”“模型叙述”和“展示”分开，目的是让模型不能伪造数据，也让模型不可用时研究功能仍然可用。

## 1. 先建立四个边界

```text
Provider Adapter       负责访问公开数据源和解析供应商响应
Normalized Domain      负责统一字段、单位、时间和来源
Deterministic Analysis 负责指标、质量评分、方向和证据冲突
LLM / Presentation      负责受限叙述、报告编排和页面展示
```

模型不是数据库、计算器或任意网络代理。它接收应用先整理好的数据，生成可以被服务端校验的叙述；方向、证据状态、来源和缺失项仍由 Java 侧控制。

## 2. 默认主链路：官方 FinRobot 异步报告

当前默认引擎为 `official`。从 `finrobot-official-view.js`、`OfficialFinRobotController` 开始阅读：

```text
顶部全局模型选择器 GET /api/ai/models
  -> POST /api/finrobot/tasks {code, modelId}
  -> OfficialFinRobotService（任务、超时、并发与取消）
  -> OfficialFinRobotWorker
  -> scripts/finrobot_worker.py
  -> third_party/finrobot 中固定版本的八个专题 Agent
  -> GET /api/finrobot/tasks/{id} 轮询进度和结果
  -> 下载 html / json / evidence 附件
```

Java 整理股票快照和财报证据，Python Worker 通过 OpenAI Agents SDK 分别执行专题。模型章节标记 `UNVERIFIED`，结构校验不等于事实核验。缺失数据、章节失败和来源限制随报告保留。默认流程没有模型或执行失败时不会伪造确定性官方报告，也不会自动调用旧同步引擎。

安装、任务状态、重启后的产物访问限制见 [官方 FinRobot 接入](../finrobot-official.md)。

### 2.1 获取快照

`ResearchAggregationService` 并行请求有界分区；`HithinkResearchGateway` 优先同花顺已覆盖能力，`ProviderResearchGateway` 提供原有数据和备用来源。所有东财请求仍共用串行 `ProviderThrottle`。

一个分区必须明确表示 `HEALTHY`、`DEGRADED`、`STALE`、`UNVERIFIED` 或 `UNAVAILABLE`，并保留 `Provenance`。行情或某个可选分区失败，不应抹去其他可用证据。

### 2.2 确定性分析与模型边界

`TechnicalAnalysisService`、财务趋势和质量评分先在 Java 中计算。模型不负责选择数据源、补造缺失值或重算数值。官方流程的模型输出、旧同步流程的确定性回退是不同路径，不能混用其状态含义。

## 3. 旧同步 FinRobot 链路与全局模型选择

`app.finrobot.engine: legacy` 可切回兼容页面；同步接口仍保留：

```text
POST /api/finrobot/research {code, modelId}
  -> FinRobotResearchService
  -> StockAgentTools.getResearchSnapshot
  -> NamedChatClientRegistry 选择命名模型
  -> SpringAiOverallReportGenerator / OverallReportValidator
  -> FinRobotReportMapper.fromOverall
```

未配置模型或生成失败时，转入 `ResearchJudgementEngine.assess`、`InstitutionalReportComposer.fallbackWithDiagnostic` 和 `FinRobotReportMapper.fromInstitutional`，返回带缺失项、冲突和免责声明的确定性结果。已有的 UZI 证据可作为旧同步模型链路的补充上下文。

旧文档中的 `/api/agent/analyze`、`/api/agent/overall-report` 和 `/api/agent/models` 已不是当前 REST 入口；代码中保留的报告类也不意味着同名端点仍存在。财报专项使用 `/api/agent/financial-report`，周期和 UZI 使用各自异步任务接口。

页面统一消费 `/api/ai/models`，`model-selection.js` 保存全局选择，FinRobot、周期、UZI、财报请求复用同一 `modelId`。兼容的各模块模型目录仍保留；连通性探测不代表工具调用和报告质量已通过验证。

## 4. 数据模型为什么要有 `DataSection`

普通的 `null` 只能表示“没有值”，无法区分“供应商失败”“请求成功但为空”“缓存过期”“数据未交叉验证”和“本来就没有这项数据”。`DataSection<T>` 用状态、payload、provenance 和 issues 把这些情况显式化：

```text
status       当前可用性和可信状态
payload      规范化数据；UNAVAILABLE 时必须为空
provenance   来源、来源时间、抓取时间、缓存和回退 Provider
issues       对缺失、冲突、过期或解析问题的解释
```

## 5. Provider 学习路径

阅读顺序建议是：

1. `ProviderHttpClient`：统一超时、重试、敏感信息脱敏和健康状态。
2. `ProviderThrottle`：理解为什么 Eastmoney 请求必须全局串行并带间隔抖动。
3. `ProviderHealthRegistry`：理解失败冷却如何避免持续撞击被限制的来源。
4. `HithinkResearchGateway`、`QuoteMerger` 和 `ProviderResearchGateway`：理解优先来源、字段补齐、备用来源和区块级失败。
5. `marketdata.provider.*`：阅读供应商响应解析，不让供应商字段泄漏到上层。
6. `marketdata.model.*`：阅读统一后的领域记录和单位约束。

## 6. Learning Agent 支线

`agent.learning` 是用于学习 Spring AI Agent 机制的独立示例：

```text
LearningAgentController
  -> LearningAgentFacade
  -> Chat Memory
  -> TraceAdvisor / RagAdvisor
  -> ResearchRetriever
  -> DeterministicEmbeddingModel
  -> InMemoryResearchVectorStore
  -> ResearchMcpToolProvider
  -> ChatClient（可选）或 evidence-only 响应
```

这条支线演示 Advisor、RAG、MCP 和记忆如何组合，但工具仍然只允许查询受限股票数据，不提供任意 URL、文件系统或命令执行能力。

## 7. 前端如何阅读

- `api.js`：只负责请求地址、HTTP 方法和响应解包。
- `app.js`：负责页面状态、异步请求竞态、模型选择和错误反馈。
- `views.js`：负责快照和旧同步报告展示；`finrobot-official-view.js`、`cycle-view.js`、`uzi-view.js`、`financial-view.js` 分别负责专题页面。
- `technical-view.js`：负责 ECharts 技术图表和指标卡。
- `derived-market-view.js`：负责从规范化数据派生页面展示值。
- `styles.css`：负责研究工作台的语义 token、响应式布局和状态颜色。

前端不能用颜色替代状态文字，也不能把缺失数据渲染成 0。看到 `UNAVAILABLE` 或 `STALE` 时，应回到后端响应中的 `issues` 和 `provenance` 找原因。

## 8. 测试如何阅读

单元测试优先验证确定性规则和数据契约；模型测试使用替身 `NarrativeGenerator` 或 `OverallReportGenerator`，不访问真实外部 API；UI 测试用路由拦截和 Fixture 验证页面状态、模型选择、竞态请求及降级展示。带 `external` 标签的测试才允许依赖真实供应商。

## 9. 推荐学习顺序

1. `docs/architecture/data-flow.md`
2. `StockResearchSnapshot`、`DataSection`、`Provenance`
3. `ResearchAggregationService`
4. `ResearchJudgementEngine`
5. `OfficialFinRobotService`、`OfficialFinRobotWorker` 和 `scripts/finrobot_worker.py`
6. `InstitutionalReportComposer`、`ReportValidator`
7. `FinRobotResearchService`、`SpringAiOverallReportGenerator`
8. `OverallReportValidator`、`FinancialReportService` 和专题报告校验
9. Provider、RAG、MCP、Memory 和前端

