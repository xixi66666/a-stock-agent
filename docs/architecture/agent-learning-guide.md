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

## 2. 主链路：生成研究报告

点击页面的“生成研究报告”后，入口在 `static/js/app.js` 的 `bindAgentAction`：

```text
按钮
  -> stockApi.analyze(code)
  -> POST /api/agent/analyze
  -> AgentController.analyze
  -> StockAnalysisAgent.analyzeInstitutional
  -> StockAgentTools.getResearchSnapshot
  -> ResearchAggregationService.research
  -> ResearchGateway / Provider Adapters
  -> StockResearchSnapshot
  -> ResearchJudgementEngine.assess
  -> InstitutionalReportComposer.compose
  -> SpringAiNarrativeGenerator.generate（模型已配置时）
  -> ReportValidator.validate
  -> InstitutionalReportComposer.assembleValidated
  -> JSON
  -> renderInstitutionalReport
```

### 2.1 获取快照

`ResearchAggregationService` 并行请求行情、K 线和可选研究分区，然后把每个结果包装成 `DataSection<T>`。一个分区不可以因为失败而伪造空值；它必须明确表示 `HEALTHY`、`DEGRADED`、`STALE`、`UNVERIFIED` 或 `UNAVAILABLE`，并保留 `Provenance`。

### 2.2 确定性分析

`ResearchJudgementEngine` 使用固定权重和阈值分析技术、资金、事件、基本面和估值。这个阶段不调用模型，因为指标计算、缺失数据处理和冲突识别必须可重复、可测试、可解释。

### 2.3 构造有界证据包

`InstitutionalReportComposer` 只把允许模型使用的事实、信号、事件、缺失项和冲突放入 `ReportEvidencePackage`。证据包中的每条内容都有稳定 ID，例如 `[quote-price]`。Prompt 要求模型引用这些 ID，服务端再检查引用是否真实存在。

### 2.4 模型叙述与回退

模型只能生成叙述字段，不能改变 Java 已确定的方向和证据状态。返回后依次执行：

1. 反序列化为 `ReportNarrativeDraft`。
2. 检查空字段、长度、数字、证据 ID、冲突说明和交易指令。
3. 如果是阻断问题，最多请求一次修复。
4. 修复仍失败或模型调用异常时，回到确定性叙述。

所以页面看到的 `MODEL_ASSISTED`、`MODEL_ASSISTED_PARTIAL` 或 `DETERMINISTIC_FALLBACK`，是一次真实的生成路径说明，而不是模型自报状态。

## 3. 总体报告链路

总体报告是独立的第二条链路，不使用研究报告按钮的模型选择：

```text
页面选择模型
  -> GET /api/agent/models?capability=overall-report
  -> POST /api/agent/overall-report {code, modelId}
  -> OverallReportService
  -> NamedChatClientRegistry
  -> StockAgentTools.getResearchSnapshot
  -> SpringAiOverallReportGenerator
  -> OverallReportValidator
  -> OverallResearchReport
```

它允许本次请求选择命名模型，模型读取完整规范化快照，服务端校验报告章节、来源引用、数字边界和免责声明。总体报告失败不会覆盖右侧研究报告。

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
4. `ProviderResearchGateway`：理解优先来源、备用来源和区块级失败。
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
- `views.js`：负责把快照、研究报告和总体报告渲染为 HTML。
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
5. `StockAnalysisAgent`
6. `InstitutionalReportComposer`、`ReportValidator`
7. `SpringAiNarrativeGenerator`
8. `OverallReportService` 和总体报告校验
9. Provider、RAG、MCP、Memory 和前端

