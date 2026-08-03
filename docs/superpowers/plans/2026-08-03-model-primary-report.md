# 模型主叙述报告 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将模型叙述改为报告主内容，并按字段保留有效模型内容、局部回退无效字段。

**Architecture:** 确定性分析继续提供事实底座和不可变方向；`ReportValidator` 返回字段级阻断问题与软警告；`StockAnalysisAgent` 选择完整模型、带警告模型或部分模型模式，`InstitutionalReportComposer` 只替换失败字段。

**Tech Stack:** Java 21、Spring Boot、JUnit 5、AssertJ、Playwright。

---

### Task 1: 扩展生成模式与字段级校验结果

**Files:**
- Modify: `src/main/java/com/astock/agent/agent/report/GenerationMode.java`
- Modify: `src/main/java/com/astock/agent/agent/report/ReportValidator.java`
- Test: `src/test/java/com/astock/agent/agent/report/ReportValidatorTest.java`

- [x] 写失败测试：未知证据和交易关键词只阻断对应字段；缺少冲突只产生 warning；有效字段没有阻断问题。
- [x] 运行 `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ReportValidatorTest' test`，确认测试先失败。
- [x] 增加 `MODEL_ASSISTED_WITH_WARNINGS`、`MODEL_ASSISTED_PARTIAL`，并让验证结果提供 `issuesByField()`、`blockingIssues()`、`warnings()` 和字段阻断查询。
- [x] 重新运行聚焦测试，确认通过。

### Task 2: 局部组装模型报告

**Files:**
- Modify: `src/main/java/com/astock/agent/agent/report/InstitutionalReportComposer.java`
- Modify: `src/main/java/com/astock/agent/agent/StockAnalysisAgent.java`
- Modify: `src/main/java/com/astock/agent/agent/report/ModelFailureClassifier.java`
- Test: `src/test/java/com/astock/agent/agent/StockAnalysisAgentTest.java`
- Test: `src/test/java/com/astock/agent/agent/report/InstitutionalReportComposerTest.java`

- [x] 写失败测试：模型摘要和基本面有效、技术叙述含交易词时，结果为 `MODEL_ASSISTED_PARTIAL`，有效字段保留模型文本，技术字段使用确定性叙述。
- [x] 写失败测试：只有缺少冲突警告时，结果为 `MODEL_ASSISTED_WITH_WARNINGS` 且仍保留模型叙述。
- [x] 运行两个聚焦测试类确认 RED。
- [x] 增加带校验结果的组装入口和验证警告诊断；只按阻断字段选择确定性文本。
- [x] 运行聚焦测试确认 GREEN。

### Task 2A: 一次模型叙述修复

**Files:**
- Modify: `src/main/java/com/astock/agent/agent/report/NarrativeGenerator.java`
- Modify: `src/main/java/com/astock/agent/agent/report/SpringAiNarrativeGenerator.java`
- Modify: `src/main/java/com/astock/agent/agent/StockAnalysisAgent.java`
- Test: `src/test/java/com/astock/agent/agent/StockAnalysisAgentTest.java`

- [x] 写失败测试：首次草稿包含交易关键词，修复草稿通过校验后，结果为 `MODEL_ASSISTED`。
- [x] 运行测试确认 RED。
- [x] 增加默认修复入口和 Spring AI 修复提示词；阻断校验最多触发一次修复。
- [x] 运行测试确认 GREEN。

### Task 3: UI 显示模型主报告状态

**Files:**
- Modify: `src/main/resources/static/js/app.js`
- Test: `tests/ui/dashboard.spec.js`

- [x] 添加带 `MODEL_ASSISTED_WITH_WARNINGS` 与 `MODEL_ASSISTED_PARTIAL` 的报告 fixture，并断言模型正文和诊断同时可见。
- [x] 运行聚焦 Playwright 测试确认 RED。
- [x] 调整状态标签和诊断文案，让部分回退明确显示失败字段，不覆盖有效模型模块。
- [x] 运行完整 UI 测试确认 GREEN。

### Task 4: 全量验证

**Files:**
- Verify: `git diff --check`、相关 Java 测试、`tests/ui/dashboard.spec.js`

- [x] 使用项目 JDK 21 运行相关 Java 测试。
- [x] 使用隔离应用端口 `10002` 运行完整 UI 测试。
- [x] 检查无敏感配置、凭据或调试日志进入变更文件。
