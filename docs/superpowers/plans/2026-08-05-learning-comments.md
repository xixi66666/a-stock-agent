# 全项目 Agent 学习注释实施计划

> **For agentic workers:** 本计划用于当前会话的分模块执行；任务只涉及注释和文档，不改变运行时行为。

**Goal:** 为整个项目建立从数据源到 Agent 输出的中文学习型注释体系。

**Architecture:** 用模块学习总览和核心类注释覆盖所有 Java 逻辑包，用复杂方法注释解释关键数据流，再用一份 Agent 学习总览文档把研究报告、总体报告、RAG/MCP 和前端链路串起来。简单 DTO 只说明字段语义，避免重复实现代码。

**Tech Stack:** Java 21、Spring Boot、Spring AI ChatClient、JavaScript、Playwright UI fixtures、Markdown。

---

### Task 1：建立模块地图和学习总览

**Files:**

- Create: `docs/architecture/agent-learning-guide.md`
- Modify: `docs/architecture/data-flow.md`，补充源码入口和学习顺序

- [ ] 描述核心研究报告链路、总体报告链路和学习型 Agent 支线。
- [ ] 为每个 Java 包、前端入口和测试目录列出入口文件。
- [ ] 明确确定性逻辑、模型逻辑、数据来源和 API 层的边界。

### Task 2：覆盖 Java 包级职责

**Files:**

- Modify: `docs/architecture/agent-learning-guide.md`
- Modify: 各模块核心 Java 类的类级注释

- [ ] 为 `agent`、`analysis`、`marketdata`、`technical`、`web`、`config` 及其子包在学习总览中添加模块职责说明。
- [ ] 在核心类注释中写明上游、下游、学习重点和职责边界。
- [ ] 不在包说明中重复类实现细节。

### Task 3：补充核心 Agent 流程注释

**Files:**

- Modify: `StockAnalysisAgent.java`
- Modify: `StockAgentTools.java`
- Modify: `AgentConfiguration.java`
- Modify: `InstitutionalReportComposer.java`
- Modify: `SpringAiNarrativeGenerator.java`
- Modify: `ReportValidator.java`
- Modify: `ModelFailureClassifier.java`
- Modify: `OverallReportService.java`
- Modify: `SpringAiOverallReportGenerator.java`
- Modify: `OverallReportValidator.java`

- [ ] 解释固定研究报告如何先做确定性判断，再允许模型只生成受限叙述。
- [ ] 解释证据包为什么有界、如何引用证据、为什么不能让模型改变方向。
- [ ] 解释结构化输出、校验、一次修复和确定性回退。
- [ ] 解释总体报告为何使用独立模型角色和独立服务。

### Task 4：补充数据、分析和 Provider 注释

**Files:**

- Modify: `ResearchAggregationService.java`
- Modify: `StockResearchSnapshot.java`
- Modify: `DataQualityScorer.java`
- Modify: `ResearchJudgementEngine.java`
- Modify: `ModuleAnalysisFactory.java`
- Modify: `DataSection.java`
- Modify: `Provenance.java`
- Modify: Provider HTTP、限流、健康注册和主要 Provider 适配器

- [ ] 解释原始响应、规范化记录、快照和来源元数据之间的转换。
- [ ] 解释部分成功、空成功、过期、未验证和不可用状态的区别。
- [ ] 解释优先 Provider、Eastmoney 限流和局部失败原则。
- [ ] 解释确定性指标如何产生，且不交给模型计算。

### Task 5：补充 Learning、前端和测试注释

**Files:**

- Modify: `agent/learning` 下的 Facade、Advisor、RAG、MCP、Memory、Embedding、Vector Store
- Modify: `src/main/resources/static/js/*.js`、`index.html`、`styles.css`
- Modify: `tests/ui/dashboard.spec.js` 和主要 Fixture

- [ ] 解释学习型 Agent 与机构研究报告 Agent 的区别。
- [ ] 解释 Advisor 链、检索上下文、受限工具和会话记忆。
- [ ] 解释前端状态如何对应后端响应状态。
- [ ] 解释 UI 测试在验证什么，以及为什么默认离线。

### Task 6：轻量验证

- [ ] 运行 `git diff --check`。
- [ ] 设置仓库 JDK 21 并运行 `mvnw.cmd -Dmaven.repo.local=.m2/repository -DskipTests compile`。
- [ ] 复核差异统计、变更文件和注释乱码。
