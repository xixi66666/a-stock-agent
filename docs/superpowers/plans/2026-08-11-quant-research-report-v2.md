# QuantResearchReport v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with the red-green-refactor loop.

**Goal:** 新增一份不含任何打分、以客观指标和证据化文字为核心的单股量化研究报告，并保持旧研究报告与总体报告接口兼容。

**Architecture:** 新增 QuantResearchReportService，读取现有 StockResearchSnapshot，通过独立 BenchmarkDataGateway 获取沪深 300、中证 500、中证 1000 的可用日线，再由 QuantFactsCalculator 计算可复核指标。QuantReportComposer 生成确定性事实和回退文字，SpringAiQuantNarrativeGenerator 可选地将事实包组织成受限中文段落，QuantNarrativeValidator 校验数字、来源和禁用内容后组装 QuantResearchReport。

**Tech Stack:** Java 21, Spring Boot, Spring AI ChatClient, Jackson, JUnit 5, AssertJ, Mockito, Playwright, 现有 Tencent/Baidu/Eastmoney/Sina/CNInfo 适配器和语义 UI token。

---

## 文件地图

- Create: src/main/java/com/astock/agent/marketdata/model/BenchmarkId.java - 指数标识和腾讯代码映射。
- Create: src/main/java/com/astock/agent/marketdata/provider/BenchmarkDataGateway.java - 基准日线读取边界。
- Create: src/main/java/com/astock/agent/marketdata/provider/tencent/TencentBenchmarkDataGateway.java - 腾讯指数适配器。
- Modify: src/main/java/com/astock/agent/marketdata/provider/tencent/TencentResponseParser.java - 按显式腾讯代码解析日线。
- Modify: src/main/java/com/astock/agent/config/MarketDataConfiguration.java - 注册基准网关。
- Create: src/main/java/com/astock/agent/agent/quant/MetricAvailability.java
- Create: src/main/java/com/astock/agent/agent/quant/MetricObservation.java
- Create: src/main/java/com/astock/agent/agent/quant/BenchmarkComparison.java
- Create: src/main/java/com/astock/agent/agent/quant/QuantReportFacts.java
- Create: src/main/java/com/astock/agent/agent/quant/QuantFactsCalculator.java
- Create: src/main/java/com/astock/agent/agent/quant/QuantResearchReport.java
- Create: src/main/java/com/astock/agent/agent/quant/QuantNarrativeDraft.java
- Create: src/main/java/com/astock/agent/agent/quant/QuantNarrativeGenerator.java
- Create: src/main/java/com/astock/agent/agent/quant/SpringAiQuantNarrativeGenerator.java
- Create: src/main/java/com/astock/agent/agent/quant/QuantNarrativeValidator.java
- Create: src/main/java/com/astock/agent/agent/quant/QuantReportComposer.java
- Create: src/main/java/com/astock/agent/agent/quant/QuantResearchReportService.java
- Modify: src/main/java/com/astock/agent/agent/AgentConfiguration.java
- Modify: src/main/java/com/astock/agent/web/AgentController.java
- Modify: src/main/resources/static/js/api.js, app.js, views.js, styles.css
- Create/Modify: corresponding Java and Playwright tests.

## Task 1: 基准指数适配器

**Files:**
- Create BenchmarkId.java, BenchmarkDataGateway.java, TencentBenchmarkDataGateway.java
- Modify TencentResponseParser.java and MarketDataConfiguration.java
- Test TencentBenchmarkDataGatewayTest.java

- [ ] Step 1: 写基准解析红测试

新增 fixture 解析测试：响应含 sh000300 时返回至少 260 根升序 DailyBar；缺少指定代码、日期冲突、OHLC 不合法和样本不足分别返回异常或不可用状态。

~~~java
@Test
void parsesSupportedBenchmarkBarsWithIdentityAndOrdering() {
    List<DailyBar> bars = parser.parseDailyBars(fixture("tencent/index-000300.json"), "sh000300");
    assertThat(bars).hasSizeGreaterThanOrEqualTo(260)
            .extracting(DailyBar::date).isSorted();
}
~~~

- [ ] Step 2: 运行红测试

~~~powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=TencentBenchmarkDataGatewayTest' test
~~~

Expected: FAIL，因为当前解析器没有按显式指数代码读取的 API。

- [ ] Step 3: 实现最小适配器

BenchmarkId 只包含 CSI_300(sh000300, 沪深300)、CSI_500(sh000905, 中证500)、CSI_1000(sh000852, 中证1000)。TencentBenchmarkDataGateway 调用现有 ProviderHttpClient，数量固定 520，使用 ProviderId.TENCENT。解析器增加 parseDailyBars(body, tencentCode)，股票重载委托给它。异常返回 DataSection.unavailable，不使用个股数据替代。配置类注册 BenchmarkDataGateway Bean。

- [ ] Step 4: 运行绿测试并检查契约

~~~powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=TencentBenchmarkDataGatewayTest' test
git diff --check
~~~

Expected: PASS，且 diff check 无输出。

- [ ] Step 5: 提交

~~~powershell
git add src/main/java/com/astock/agent/marketdata src/main/java/com/astock/agent/config/MarketDataConfiguration.java src/test/java/com/astock/agent/marketdata/provider/tencent/TencentBenchmarkDataGatewayTest.java
git commit -m "feat: add benchmark data gateway"
~~~

## Task 2: 无打分量化指标计算

**Files:**
- Create src/main/java/com/astock/agent/agent/quant/MetricAvailability.java
- Create MetricObservation.java, BenchmarkComparison.java, QuantReportFacts.java, QuantFactsCalculator.java
- Test src/test/java/com/astock/agent/agent/quant/QuantFactsCalculatorTest.java

- [ ] Step 1: 写已知序列红测试

使用固定 260 根日线构造器，验证 20 日收益、年化波动、最大回撤起止日期、历史 VaR/CVaR 样本不足、基准日期不重叠时 Beta/超额收益/IR 不可用。通过反射或 JSON 字段断言确认返回对象没有 score、rank、confidence 或 aggregate 字段。

~~~java
@Test
void calculatesObservableMetricsWithoutCompositeScore() {
    QuantReportFacts facts = calculator.calculate(snapshotWithBars(260), Map.of());
    assertThat(facts.metric("return-20").value()).isEqualByComparingTo("0.10");
    assertThat(facts.toString()).doesNotContain("score", "rank", "confidence");
}
~~~

- [ ] Step 2: 运行红测试

~~~powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=QuantFactsCalculatorTest' test
~~~

Expected: FAIL，因为 v2 类型和计算器尚不存在。

- [ ] Step 3: 实现指标类型和计算器

MetricObservation 固定包含 name、value、unit、window、asOf、availability、method、sourceIds、limitations。值为空时 value 必须为 null。QuantFactsCalculator 实现收益、年化波动、下行波动、最大回撤、Sharpe（无风险利率 0，写入 method）、Sortino、Calmar、历史 VaR/CVaR、偏度、峰度、Beta 和 Information Ratio。计算前校验非空、正价格、日期升序和最小样本；异常输入返回不可用事实而不是 0。QuantReportFacts 只保存客观指标、文字事实、来源和限制，不保存任何组合分数。

- [ ] Step 4: 运行绿测试并回归现有技术测试

~~~powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=QuantFactsCalculatorTest,TechnicalAnalysisServiceTest' test
~~~

Expected: PASS。

- [ ] Step 5: 提交

~~~powershell
git add src/main/java/com/astock/agent/agent/quant src/test/java/com/astock/agent/agent/quant/QuantFactsCalculatorTest.java
git commit -m "feat: add score-free quantitative metrics"
~~~

## Task 3: v2 报告契约、事实编排和模型校验

**Files:**
- Create QuantResearchReport.java, QuantNarrativeDraft.java, QuantNarrativeGenerator.java, SpringAiQuantNarrativeGenerator.java, QuantNarrativeValidator.java, QuantReportComposer.java
- Test QuantNarrativeValidatorTest.java and QuantReportComposerTest.java

- [ ] Step 1: 写模型输出校验红测试

事实包中没有的数字、交易指令、打分词、引用不可用数据的确定性结论必须失败；只使用事实包数字的普通研究文字必须通过。

~~~java
@Test
void rejectsUnsupportedNumbersTradeInstructionsAndScores() {
    QuantNarrativeValidator.Validation result = validator.validate(
            new QuantNarrativeDraft("目标价 999", "", "综合得分 80", "", "", "", ""), facts);
    assertThat(result.blockingIssues())
            .contains("UNSUPPORTED_NUMBER", "TRADE_INSTRUCTION", "SCORING_CONTENT");
}
~~~

- [ ] Step 2: 运行红测试

~~~powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=QuantNarrativeValidatorTest,QuantReportComposerTest' test
~~~

Expected: FAIL，因为 v2 草稿、校验器和组装器尚不存在。

- [ ] Step 3: 实现契约、模型适配器和组装器

QuantResearchReport 只包含元数据、事实分区、文字段落、组合范围、来源和可选诊断，不增加 score/rank/confidence 字段。SpringAiQuantNarrativeGenerator 使用 ChatClient，system prompt 要求只返回 QuantNarrativeDraft JSON，user prompt 只放 QuantReportFacts，不传动态 tools。QuantNarrativeValidator 用事实包中的规范化数字集合检查模型数字，用关键词集合拒绝交易指令和打分内容，并检查不可用分区不得写成具体结论。QuantReportComposer 对失败字段使用确定性中文段落回退，始终保留事实、来源、缺失数据和方法限制。

- [ ] Step 4: 运行绿测试

~~~powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=QuantNarrativeValidatorTest,QuantReportComposerTest' test
~~~

Expected: PASS。

- [ ] Step 5: 提交

~~~powershell
git add src/main/java/com/astock/agent/agent/quant src/test/java/com/astock/agent/agent/quant/QuantNarrativeValidatorTest.java src/test/java/com/astock/agent/agent/quant/QuantReportComposerTest.java
git commit -m "feat: compose validated quantitative report text"
~~~

## Task 4: 服务编排和 REST API

**Files:**
- Create QuantResearchReportService.java and QuantResearchReportServiceTest.java
- Modify AgentConfiguration.java, AgentController.java and AgentControllerTest.java

- [ ] Step 1: 写服务和 Controller 红测试

服务测试验证：模型未配置时返回确定性文字；某一指数失败时其他基准和个股报告仍可用；模型非法数字时对应段落回退；组合级字段始终 UNAVAILABLE。Controller 测试验证：

~~~java
mockMvc.perform(post("/api/agent/quant-report")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"600519\"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.reportMeta.reportType").value("QUANT_SINGLE_SECURITY"))
    .andExpect(jsonPath("$.portfolioScope.scope").value("SINGLE_SECURITY"));
~~~

另一个测试继续验证 /api/agent/analyze 返回旧 InstitutionalResearchReport。

- [ ] Step 2: 运行红测试

~~~powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=QuantResearchReportServiceTest,AgentControllerTest' test
~~~

Expected: 新 endpoint 测试 FAIL，旧 endpoint 测试保持现有状态。

- [ ] Step 3: 实现服务和 API

QuantResearchReportService.generate(String code) 先通过 StockAgentTools 读取同一快照，再读取三个基准，调用 QuantFactsCalculator，根据 institutional-report 角色可用性选择模型叙述或确定性回退，最后返回 v2 报告。AgentController 增加可选构造器参数和 @PostMapping("/quant-report")，保留现有构造器和 /analyze 路由。AgentConfiguration 注册服务，没有模型时仍创建服务。

- [ ] Step 4: 运行绿测试

~~~powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=QuantResearchReportServiceTest,AgentControllerTest' test
~~~

Expected: PASS。

- [ ] Step 5: 提交

~~~powershell
git add src/main/java/com/astock/agent/agent/quant/QuantResearchReportService.java src/main/java/com/astock/agent/agent/AgentConfiguration.java src/main/java/com/astock/agent/web/AgentController.java src/test/java/com/astock/agent/agent/quant/QuantResearchReportServiceTest.java src/test/java/com/astock/agent/web/AgentControllerTest.java
git commit -m "feat: expose quantitative research report api"
~~~

## Task 5: 前端纯文字报告

**Files:**
- Modify src/main/resources/static/js/api.js, app.js, views.js, styles.css
- Modify tests/ui/dashboard.spec.js

- [ ] Step 1: 写 UI 红测试

新增 Playwright mock /api/agent/quant-report，验证报告包含摘要、基准表、因子文字、风险、失效条件、组合级不可用说明和来源；验证页面不包含 score/rank/confidence 文本，不渲染雷达图或评分控件；模型失败时事实回退仍可见。

~~~javascript
test("quant report renders evidence-backed prose without scores", async ({ page }) => {
  await page.route("**/api/agent/quant-report", route => route.fulfill({ json: quantReportFixture() }));
  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();
  await page.getByRole("button", { name: "生成研究报告" }).click();
  await expect(page.locator("#agent-output")).toContainText("市场环境与基准表现");
  await expect(page.locator("#agent-output")).toContainText("组合级数据不可用");
  await expect(page.locator("#agent-output")).not.toContainText("综合得分");
});
~~~

- [ ] Step 2: 运行红测试

~~~powershell
npm.cmd run test:ui -- --grep "quant report renders evidence-backed prose without scores"
~~~

Expected: FAIL，因为客户端仍请求旧 endpoint，且没有 v2 renderer。

- [ ] Step 3: 实现 API 和渲染

stockApi.quantReport(code) 请求新 endpoint。Agent 页面按钮改用新请求和独立 requestId，保留总体报告的竞态隔离。新增 renderQuantResearchReport，按元数据、摘要、市场、个股、因子文字、估值基本面、资金事件、风险、前瞻、组合不可用、来源方法顺序渲染。事实表只展示值、单位、窗口、状态和来源；不增加评分仪表盘、进度条或雷达图。CSS 使用现有语义 token，桌面两列事实布局，中间尺寸降为一列，移动端完整换行。

- [ ] Step 4: 运行 UI 绿测试和静态资源测试

~~~powershell
npm.cmd run test:ui -- --grep "quant report"
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=StaticResourceTest' test
~~~

Expected: PASS；四个规定视口无横向溢出。

- [ ] Step 5: 提交

~~~powershell
git add src/main/resources/static/js/api.js src/main/resources/static/js/app.js src/main/resources/static/js/views.js src/main/resources/static/styles.css tests/ui/dashboard.spec.js
git commit -m "feat: render score-free quantitative report"
~~~

## Task 6: 全量验证和交付检查

- [ ] Step 1: 运行完整离线测试

~~~powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
~~~

Expected: PASS；外部测试不因默认离线测试而被触发。

- [ ] Step 2: 运行 UI 全量测试

~~~powershell
npm.cmd run test:ui
~~~

Expected: PASS，截图检查固定头部、长文本、按钮和事实表无重叠。

- [ ] Step 3: 运行格式和秘密检查

~~~powershell
git diff --check
rg -n -i "api[-_ ]?key|authorization|bearer|secret|token" src/main/java/com/astock/agent/agent/quant src/main/resources/static docs/superpowers/plans/2026-08-11-quant-research-report-v2.md
~~~

Expected: 只有配置字段名、脱敏错误码和文档规则说明，不出现真实凭据。

- [ ] Step 4: 检查变更范围

~~~powershell
git status --short
git log -8 --oneline
~~~

确认只包含 v2 功能、测试和必要文档；保留用户已有的 out/ 和 tests/ui/fixtures/README.md 未跟踪文件，不执行清理或回滚。

