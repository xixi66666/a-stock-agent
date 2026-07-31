# Institutional Agent Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前数字罗列式 Agent 报告升级为面向 1-3 个月的机构风格研究报告，由确定性 Java 规则生成方向和证据边界，大模型只负责基于冻结证据生成叙述。

**Architecture:** 现有 Provider 和 `ResearchAggregationService` 继续负责真实数据与来源；新增行业估值和机构一致预期计算，再由 `ResearchJudgementEngine` 生成不可被模型修改的 `DeterministicAssessment`。`InstitutionalReportComposer` 生成有界证据包，`ChatClient` 仅返回叙述草稿，`ReportValidator` 校验后由 Java 组装最终报告；模型异常时返回确定性模板。

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, JDK HttpClient, Jackson, Caffeine, ta4j, JUnit 5, AssertJ, MockWebServer, MockMvc, Playwright 1.55.1

---

## File Map

### New production files

- `src/main/java/com/astock/agent/marketdata/model/IndustryPeerQuote.java`: 同行业成分股的同口径估值记录。
- `src/main/java/com/astock/agent/marketdata/model/IndustryValuationData.java`: 行业样本、目标估值、中位数和分位结果。
- `src/main/java/com/astock/agent/analysis/institutional/Direction.java`: `STRONGER/NEUTRAL/WEAKER/INSUFFICIENT`。
- `src/main/java/com/astock/agent/analysis/institutional/EvidenceStatus.java`: `SUFFICIENT/PARTIAL/INSUFFICIENT`。
- `src/main/java/com/astock/agent/analysis/institutional/ReportEvidence.java`: 可引用的事实证据。
- `src/main/java/com/astock/agent/analysis/institutional/ConsensusForecast.java`: EPS 中位数、覆盖数量、分歧和评级分布。
- `src/main/java/com/astock/agent/analysis/institutional/ConsensusForecastCalculator.java`: 从研报确定性聚合一致预期。
- `src/main/java/com/astock/agent/analysis/institutional/IndustryValuationCalculator.java`: 清洗同行样本并计算行业分位。
- `src/main/java/com/astock/agent/analysis/institutional/IndustryValuationService.java`: 组合行业分类、同行批量查询、缓存和分位计算。
- `src/main/java/com/astock/agent/analysis/institutional/DeterministicAssessment.java`: 方向、证据状态、驱动、约束、风险、冲突和失效条件。
- `src/main/java/com/astock/agent/analysis/institutional/ResearchJudgementEngine.java`: 五维内部评分和方向标签。
- `src/main/java/com/astock/agent/agent/report/GenerationMode.java`: 模型辅助或确定性降级。
- `src/main/java/com/astock/agent/agent/report/ReportEvidencePackage.java`: 冻结后交给模型的有界证据包。
- `src/main/java/com/astock/agent/agent/report/ReportNarrativeDraft.java`: 模型允许返回的叙述字段。
- `src/main/java/com/astock/agent/agent/report/InstitutionalResearchReport.java`: 最终 API 报告契约。
- `src/main/java/com/astock/agent/agent/report/InstitutionalReportComposer.java`: 快照和判断转证据包、确定性模板。
- `src/main/java/com/astock/agent/agent/report/ReportValidator.java`: 证据引用、数字和安全边界校验。

### Modified production files

- `src/main/java/com/astock/agent/marketdata/provider/eastmoney/EastmoneyResearchClient.java`: 解析并批量获取行业成分估值。
- `src/main/java/com/astock/agent/analysis/ResearchGateway.java`: 新增行业估值分区。
- `src/main/java/com/astock/agent/analysis/ProviderResearchGateway.java`: 路由东方财富行业估值。
- `src/main/java/com/astock/agent/analysis/StockResearchSnapshot.java`: 增加 `industryValuation` 分区。
- `src/main/java/com/astock/agent/analysis/ResearchAggregationService.java`: 并发聚合新分区。
- `src/main/java/com/astock/agent/config/CacheConfiguration.java`: 增加行业估值、研报、新闻和公告缓存。
- `src/main/java/com/astock/agent/config/MarketDataConfiguration.java`: 注册新计算器、判断引擎和报告组件。
- `src/main/java/com/astock/agent/agent/StockAnalysisAgent.java`: 改为“判断 -> 证据包 -> 叙述 -> 校验 -> 组装”。
- `src/main/java/com/astock/agent/web/AgentController.java`: 返回新报告契约。
- `src/main/resources/static/js/app.js`: 渲染完整报告。
- `src/main/resources/static/js/views.js`: 调整 Agent 报告容器。
- `src/main/resources/static/css/app.css`: 增加紧凑报告布局和状态样式。
- `tests/ui/dashboard.spec.js`: 覆盖新报告字段和四个视口。

---

### Task 1: Industry valuation and consensus calculators

**Files:**
- Create: `src/main/java/com/astock/agent/marketdata/model/IndustryPeerQuote.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/IndustryValuationData.java`
- Create: `src/main/java/com/astock/agent/analysis/institutional/ConsensusForecast.java`
- Create: `src/main/java/com/astock/agent/analysis/institutional/ConsensusForecastCalculator.java`
- Create: `src/main/java/com/astock/agent/analysis/institutional/IndustryValuationCalculator.java`
- Test: `src/test/java/com/astock/agent/analysis/institutional/ConsensusForecastCalculatorTest.java`
- Test: `src/test/java/com/astock/agent/analysis/institutional/IndustryValuationCalculatorTest.java`

- [ ] **Step 1: Write failing calculator tests**

```java
@Test
void usesLatestReportPerInstitutionAndMedianEps() {
    List<ResearchItem> reports = List.of(
            report("甲机构", "2026-07-01", "5.0", "6.0", "增持"),
            report("甲机构", "2026-07-20", "5.4", "6.3", "买入"),
            report("乙机构", "2026-07-18", "5.8", "6.7", "增持"),
            report("丙机构", "2026-07-19", null, "7.1", "中性"));

    ConsensusForecast result = new ConsensusForecastCalculator().calculate(reports);

    assertThat(result.coverage()).isEqualTo(3);
    assertThat(result.currentYearEpsMedian()).isEqualByComparingTo("5.6");
    assertThat(result.nextYearEpsMedian()).isEqualByComparingTo("6.7");
    assertThat(result.ratingDistribution()).containsEntry("增持", 1).containsEntry("买入", 1);
}

@Test
void removesInvalidPeAndCalculatesInclusivePercentile() {
    List<IndustryPeerQuote> peers = List.of(
            peer("600001", "10", "1.0"), peer("600002", "20", "2.0"),
            peer("600519", "30", "3.0"), peer("600004", "-5", null));

    IndustryValuationData result = new IndustryValuationCalculator()
            .calculate("BK0477", "白酒", "600519", peers);

    assertThat(result.validPeSamples()).isEqualTo(3);
    assertThat(result.peMedian()).isEqualByComparingTo("20");
    assertThat(result.pePercentile()).isEqualByComparingTo("100.00");
    assertThat(result.excludedPeSamples()).isEqualTo(1);
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ConsensusForecastCalculatorTest,IndustryValuationCalculatorTest' test
```

Expected: compilation fails because calculator and result types do not exist.

- [ ] **Step 3: Implement normalized records and calculators**

```java
public record IndustryPeerQuote(
        String code,
        String name,
        BigDecimal peDynamic,
        BigDecimal pb,
        BigDecimal totalMarketValueYuan) {}

public record IndustryValuationData(
        String industryCode,
        String industryName,
        int totalSamples,
        int validPeSamples,
        int excludedPeSamples,
        int validPbSamples,
        int excludedPbSamples,
        BigDecimal targetPe,
        BigDecimal peMedian,
        BigDecimal pePercentile,
        BigDecimal targetPb,
        BigDecimal pbMedian,
        BigDecimal pbPercentile) {}

public record ConsensusForecast(
        int coverage,
        BigDecimal currentYearEpsMedian,
        BigDecimal nextYearEpsMedian,
        BigDecimal currentYearDispersion,
        BigDecimal nextYearDispersion,
        String revisionTrend,
        Map<String, Integer> ratingDistribution) {
    public ConsensusForecast {
        ratingDistribution = ratingDistribution == null ? Map.of() : Map.copyOf(ratingDistribution);
    }
}
```

`IndustryValuationCalculator` must sort valid positive values, use the average of the two center values for an even sample count, and calculate inclusive percentile as `count(value <= target) / validCount * 100` with scale 2. Return `null` for a target metric when the target row is absent; never substitute Tencent ratios into an Eastmoney peer sample.

`ConsensusForecastCalculator` must group by nonblank institution, keep its newest report, calculate medians independently for current and next year, use `(P75-P25)/abs(median)` as robust dispersion, and return an immutable rating count map.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Step 2 command.

Expected: both calculator test classes pass with no network access.

- [ ] **Step 5: Commit the calculator slice**

```powershell
git add src/main/java/com/astock/agent/marketdata/model/IndustryPeerQuote.java src/main/java/com/astock/agent/marketdata/model/IndustryValuationData.java src/main/java/com/astock/agent/analysis/institutional src/test/java/com/astock/agent/analysis/institutional
git commit -m "feat: calculate industry valuation and consensus forecasts"
```

---

### Task 2: Eastmoney industry peer adapter and cache

**Files:**
- Create: `src/test/resources/fixtures/eastmoney/industry-peers-600519.json`
- Modify: `src/main/java/com/astock/agent/marketdata/provider/eastmoney/EastmoneyResearchClient.java`
- Modify: `src/test/java/com/astock/agent/marketdata/provider/ExtendedProviderContractTest.java`
- Test: `src/test/java/com/astock/agent/marketdata/provider/eastmoney/EastmoneyIndustryValuationClientTest.java`

- [ ] **Step 1: Add a public fixture and failing parser contract**

The fixture must contain only provider market fields and no cookies, tokens, request IDs or private headers. Use Eastmoney `clist/get` field names `f12` code, `f14` name, `f9` dynamic PE, `f23` PB and `f20` total market value.

```java
@Test
void parsesIndustryPeersWithProviderUnits() throws Exception {
    List<IndustryPeerQuote> peers = client.parseIndustryPeers(fixture("eastmoney/industry-peers-600519.json"));

    assertThat(peers).extracting(IndustryPeerQuote::code).contains("600519");
    assertThat(peers).allMatch(peer -> peer.totalMarketValueYuan() == null
            || peer.totalMarketValueYuan().signum() >= 0);
}
```

- [ ] **Step 2: Run parser test and verify RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=EastmoneyIndustryValuationClientTest' test
```

Expected: compilation fails because `parseIndustryPeers` does not exist.

- [ ] **Step 3: Implement one batch request per industry**

Add these methods to `EastmoneyResearchClient`:

```java
public List<IndustryPeerQuote> parseIndustryPeers(String body)

public DataSection<List<IndustryPeerQuote>> fetchIndustryPeers(Sector industry)
```

Build one URI per sector:

```java
URI uri = URI.create("https://push2.eastmoney.com/api/qt/clist/get"
        + "?pn=1&pz=500&po=1&np=1&fltt=2&invt=2&fid=f3"
        + "&fs=b:" + URLEncoder.encode(industry.code(), StandardCharsets.UTF_8)
        + "&fields=f12,f14,f9,f20,f23");
```

Use the first nonblank sector returned by the existing `fetchSectors` call as the provider's primary industry for the first release. Preserve the sector and peer-request provenance separately. Empty peer results are successful empty data; malformed JSON is unavailable.

缓存不放在 Provider Client 内部；Task 3 的 `IndustryValuationService` 负责缓存，保证 Adapter 只承担请求和解析。

- [ ] **Step 4: Verify parser, throttle and full provider contracts**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=EastmoneyIndustryValuationClientTest,ExtendedProviderContractTest,ProviderThrottleTest' test
```

Expected: tests pass; the client uses `ProviderHttpClient`, so Eastmoney requests remain globally serialized.

- [ ] **Step 5: Commit provider integration**

```powershell
git add src/main/java/com/astock/agent/marketdata/provider/eastmoney src/test/java/com/astock/agent/marketdata/provider src/test/resources/fixtures/eastmoney/industry-peers-600519.json
git commit -m "feat: fetch industry valuation peers"
```

---

### Task 3: Aggregate industry valuation into the research snapshot

**Files:**
- Modify: `src/main/java/com/astock/agent/analysis/ResearchGateway.java`
- Modify: `src/main/java/com/astock/agent/analysis/ProviderResearchGateway.java`
- Modify: `src/main/java/com/astock/agent/analysis/StockResearchSnapshot.java`
- Modify: `src/main/java/com/astock/agent/analysis/ResearchAggregationService.java`
- Modify: `src/main/java/com/astock/agent/config/MarketDataConfiguration.java`
- Modify: `src/main/java/com/astock/agent/config/CacheConfiguration.java`
- Create: `src/main/java/com/astock/agent/analysis/institutional/IndustryValuationService.java`
- Modify: `src/test/java/com/astock/agent/analysis/ResearchAggregationServiceTest.java`
- Test: `src/test/java/com/astock/agent/analysis/institutional/IndustryValuationServiceTest.java`
- Modify: snapshot constructor call sites found by `rg "new StockResearchSnapshot" src`

- [ ] **Step 1: Write failing aggregation tests**

```java
@Test
void industryValuationFailureRemainsSectionLocal() {
    ResearchGateway gateway = new StubGateway() {
        @Override
        public DataSection<IndustryValuationData> industryValuation(SecurityId security) {
            return DataSection.unavailable("peer batch unavailable");
        }
    };

    StockResearchSnapshot result = service(gateway).research(SecurityId.parse("600519"));

    assertThat(result.quote().status()).isEqualTo(SectionStatus.HEALTHY);
    assertThat(result.industryValuation().status()).isEqualTo(SectionStatus.UNAVAILABLE);
}
```

- [ ] **Step 2: Run aggregation test and verify RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ResearchAggregationServiceTest' test
```

Expected: compilation fails because `industryValuation` is not in the gateway or snapshot.

- [ ] **Step 3: Add the typed section through all layers**

Add to `ResearchGateway`:

```java
DataSection<IndustryValuationData> industryValuation(SecurityId security);
```

Remove `final` from the test-only `StubGateway` so the focused failure override above compiles.

`CacheConfiguration` exposes two qualified cache beans and `IndustryValuationService` owns their read-through policy:

```java
Cache<SecurityId, Sector> industryClassificationCache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofHours(24))
        .build();
Cache<String, List<IndustryPeerQuote>> industryPeerCache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofMinutes(15))
        .build();
```

Its `compare(SecurityId)` method loads the first nonblank provider-ordered sector, then one peer batch, and invokes `IndustryValuationCalculator`. Insert only successful nonempty values into caches; unavailable and empty responses remain retryable. `ProviderResearchGateway.industryValuation` delegates to this service. `ResearchAggregationService.load` submits the optional section alongside other optional sections. `StockResearchSnapshot.empty` initializes it as unavailable, and all copy methods preserve it.

Add qualified read-through caches in `ProviderResearchGateway` for slow-changing optional evidence:

```java
Cache<SecurityId, DataSection<List<ResearchItem>>> researchReportCache; // 6 hours
Cache<SecurityId, DataSection<List<NewsItem>>> newsCache;               // 30 minutes
Cache<SecurityId, DataSection<List<Announcement>>> announcementCache;  // 30 minutes
```

Only cache sections with a payload and provenance. Do not cache `UNAVAILABLE` results. Keep quote, K-line and fund-flow behavior unchanged because those sections require fresher data.

Do not make industry valuation a core availability condition. Provider failure must remain local to this section.

- [ ] **Step 4: Run all aggregation and controller contract tests**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ResearchAggregationServiceTest,StockControllerTest,StockAgentToolsTest' test
```

Expected: tests pass and existing snapshot fields remain unchanged.

- [ ] **Step 5: Commit snapshot integration**

```powershell
git add src/main/java/com/astock/agent/analysis src/main/java/com/astock/agent/config/MarketDataConfiguration.java src/main/java/com/astock/agent/config/CacheConfiguration.java src/test/java/com/astock/agent/analysis src/test/java/com/astock/agent/web/StockControllerTest.java src/test/java/com/astock/agent/agent/StockAgentToolsTest.java
git commit -m "feat: aggregate industry valuation evidence"
```

---

### Task 4: Deterministic institutional judgement engine

**Files:**
- Create: `src/main/java/com/astock/agent/analysis/institutional/Direction.java`
- Create: `src/main/java/com/astock/agent/analysis/institutional/EvidenceStatus.java`
- Create: `src/main/java/com/astock/agent/analysis/institutional/ReportEvidence.java`
- Create: `src/main/java/com/astock/agent/analysis/institutional/EvidenceScore.java`
- Create: `src/main/java/com/astock/agent/analysis/institutional/DeterministicAssessment.java`
- Create: `src/main/java/com/astock/agent/analysis/institutional/ResearchJudgementEngine.java`
- Test: `src/test/java/com/astock/agent/analysis/institutional/ResearchJudgementEngineTest.java`

- [ ] **Step 1: Write failing direction, conflict and missing-data tests**

```java
@Test
void requiresQuoteBarsAndThreeUsableDimensionsForDirection() {
    DeterministicAssessment result = engine.assess(snapshotWithOnlyQuoteAndBars());

    assertThat(result.direction()).isEqualTo(Direction.INSUFFICIENT);
    assertThat(result.evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT);
}

@Test
void keepsBullishTrendAndNegativeFlowAsExplicitConflict() {
    DeterministicAssessment result = engine.assess(snapshotWithStrongTrendAndOutflow());

    assertThat(result.conflicts()).anyMatch(text -> text.contains("趋势") && text.contains("资金"));
    assertThat(result.invalidationConditions()).isNotEmpty();
}

@Test
void missingValuationDoesNotReweightRemainingDimensions() {
    DeterministicAssessment complete = engine.assess(completeSnapshot());
    DeterministicAssessment missing = engine.assess(snapshotWithoutValuation());

    assertThat(missing.internalScore()).isEqualTo(
            complete.internalScore() - complete.dimension("VALUATION_INDUSTRY").weightedContribution());
    assertThat(missing.evidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
}
```

- [ ] **Step 2: Run judgement tests and verify RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ResearchJudgementEngineTest' test
```

Expected: compilation fails because judgement types do not exist.

- [ ] **Step 3: Implement five dimensions and stable thresholds**

Use these fixed weights:

```java
private static final Map<String, Integer> WEIGHTS = Map.of(
        "TECHNICAL_PRICE_VOLUME", 30,
        "FUND_FLOW_CAPITAL", 20,
        "EVENT_CATALYST", 20,
        "FUNDAMENTAL_EXPECTATION", 15,
        "VALUATION_INDUSTRY", 15);
```

Use these contracts so later report tasks do not depend on internal score serialization:

```java
public record ReportEvidence(
        String id,
        String title,
        String interpretation,
        String section,
        String sourceId,
        Instant observedAt) {}

public record EvidenceScore(
        String dimension,
        int rawScore,
        int weight,
        int weightedContribution,
        boolean usable,
        List<ReportEvidence> evidence) {}

public final class DeterministicAssessment {
    private final Direction direction;
    private final EvidenceStatus evidenceStatus;
    private final Map<String, EvidenceScore> dimensions;
    private final List<ReportEvidence> coreDrivers;
    private final List<String> constraints;
    private final List<String> risks;
    private final List<String> conflicts;
    private final List<String> missingData;
    private final List<String> invalidationConditions;
    private final int internalScore;

    int internalScore() { return internalScore; }
    EvidenceScore dimension(String name) { return dimensions.get(name); }
}
```

Provide public accessors for every report-facing field, immutable defensive copies in the constructor, and no public accessor for `internalScore` or `dimensions`.

Rules required in the first implementation:

- Technical: group correlated indicators; use SMA20/60/120, MACD, RETURN20/60, VOLUME_RATIO20, NATR and MAX_DRAWDOWN. RSI/KDJ overbought adds a constraint, not an automatic negative direction.
- Flow: normalize 5/20-day main flow by period turnover; financing trend, block premium, unlock ratio, shareholder change and dragon-tiger flow are bounded secondary signals.
- Events: score only typed announcements and structured capital events; news is evidence-only.
- Fundamentals: use available year-over-year revenue/profit metrics plus `ConsensusForecast`; fewer than two institutions cannot create a directional EPS signal.
- Valuation: compare target PE/PB with industry median and percentile; cheap valuation is positive only when earnings are not deteriorating.

Map total score using `>=20 STRONGER`, `<=-20 WEAKER`, otherwise `NEUTRAL`. Keep `internalScore` package-private or Jackson-ignored so it cannot enter the API. A dimension is usable only with a non-`UNAVAILABLE` section and the required payload. Direction requires both quote and bars plus at least three usable dimensions.

- [ ] **Step 4: Run judgement and quality tests**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ResearchJudgementEngineTest,DataQualityScorerTest' test
```

Expected: direction boundaries, conflicts and missing-data behavior pass.

- [ ] **Step 5: Commit deterministic judgement**

```powershell
git add src/main/java/com/astock/agent/analysis/institutional src/test/java/com/astock/agent/analysis/institutional
git commit -m "feat: add deterministic institutional judgement"
```

---

### Task 5: Report contract, evidence package and deterministic fallback

**Files:**
- Create: `src/main/java/com/astock/agent/agent/report/GenerationMode.java`
- Create: `src/main/java/com/astock/agent/agent/report/ReportEvidencePackage.java`
- Create: `src/main/java/com/astock/agent/agent/report/ReportNarrativeDraft.java`
- Create: `src/main/java/com/astock/agent/agent/report/InstitutionalResearchReport.java`
- Create: `src/main/java/com/astock/agent/agent/report/InstitutionalReportComposer.java`
- Test: `src/test/java/com/astock/agent/agent/report/InstitutionalReportComposerTest.java`

- [ ] **Step 1: Write failing evidence-package and fallback tests**

```java
@Test
void createsBoundedPackageWithStableEvidenceIds() {
    ReportEvidencePackage result = composer.compose(snapshot, assessment);

    assertThat(result.horizon()).isEqualTo("1-3个月");
    assertThat(result.evidenceCatalog()).isNotEmpty();
    assertThat(result.evidenceCatalog().keySet()).allMatch(id -> id.matches("[a-z0-9-]{3,80}"));
    assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("internalScore");
}

@Test
void deterministicFallbackPreservesConflictMissingAndDisclaimer() {
    InstitutionalResearchReport report = composer.fallback(snapshot, assessment, "model unavailable");

    assertThat(report.generationMode()).isEqualTo(GenerationMode.DETERMINISTIC_FALLBACK);
    assertThat(report.conflicts()).containsExactlyElementsOf(assessment.conflicts());
    assertThat(report.missingData()).containsExactlyElementsOf(assessment.missingData());
    assertThat(report.disclaimer()).isEqualTo("仅供学习研究，不构成投资建议");
}
```

- [ ] **Step 2: Run composer tests and verify RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=InstitutionalReportComposerTest' test
```

Expected: compilation fails because report package types do not exist.

- [ ] **Step 3: Implement immutable API contracts and fallback templates**

`InstitutionalResearchReport` must contain direction, horizon, evidence status, executive summary, core drivers, technical/flow, fundamentals/expectations, valuation/industry, catalysts, risks, conflicts, missing data, invalidation conditions, sources, generation mode, rule version, prompt version, model name, snapshot time, generated time and disclaimer.

`ReportEvidencePackage` must contain only bounded normalized evidence. Use stable evidence IDs derived from section and signal name, not text hashes. Cap ordinary news and announcements at 20 each and narrative source text at 1,000 Unicode code points per item. Do not include provider response bodies.

The fallback composer must explain relationships using deterministic templates such as trend/flow agreement, valuation/earnings alignment and explicit conflict sentences; it must not merely concatenate raw values.

- [ ] **Step 4: Run composer tests and serialization check**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=InstitutionalReportComposerTest' test
```

Expected: tests pass and Jackson serializes no internal score.

- [ ] **Step 5: Commit report contracts**

```powershell
git add src/main/java/com/astock/agent/agent/report src/test/java/com/astock/agent/agent/report
git commit -m "feat: compose institutional report evidence"
```

---

### Task 6: Model narrative validation and StockAnalysisAgent integration

**Files:**
- Create: `src/main/java/com/astock/agent/agent/report/ReportValidator.java`
- Test: `src/test/java/com/astock/agent/agent/report/ReportValidatorTest.java`
- Modify: `src/main/java/com/astock/agent/agent/StockAnalysisAgent.java`
- Modify: `src/main/java/com/astock/agent/agent/AgentConfiguration.java`
- Modify: `src/test/java/com/astock/agent/agent/StockAnalysisAgentTest.java`

- [ ] **Step 1: Write failing validator and fallback integration tests**

```java
@Test
void rejectsUnknownEvidenceInventedNumberAndTradeInstruction() {
    ReportNarrativeDraft draft = draft(
            "建议买入，目标收益30% [unknown-id]，预计利润为999亿元");

    ReportValidator.ValidationResult result = validator.validate(draft, evidencePackage());

    assertThat(result.valid()).isFalse();
    assertThat(result.issues()).contains("UNKNOWN_EVIDENCE", "UNSUPPORTED_NUMBER", "TRADE_INSTRUCTION");
}

@Test
void modelFailureReturnsDeterministicReport() {
    ChatModel failing = mock(ChatModel.class);
    when(failing.call(any(Prompt.class))).thenThrow(new IllegalStateException("simulated timeout"));
    StockAnalysisAgent agent = configuredAgent(failing, completeSnapshot());

    InstitutionalResearchReport report = agent.analyze("600519");

    assertThat(report.generationMode()).isEqualTo(GenerationMode.DETERMINISTIC_FALLBACK);
    assertThat(report.direction()).isEqualTo(assessment.direction());
}
```

- [ ] **Step 2: Run validator and agent tests and verify RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ReportValidatorTest,StockAnalysisAgentTest' test
```

Expected: compilation fails until the validator and new constructor dependencies exist.

- [ ] **Step 3: Implement constrained narrative call**

Replace the current direct `AgentResearchReport` entity call with:

```java
DeterministicAssessment assessment = judgementEngine.assess(snapshot);
ReportEvidencePackage evidence = composer.compose(snapshot, assessment);
InstitutionalResearchReport fallback = composer.fallback(snapshot, assessment, null);
if (chatClient == null || statusService.status() != AgentAvailability.READY) {
    return fallback;
}
try {
    ReportNarrativeDraft draft = chatClient.prompt()
            .system(SYSTEM_PROMPT_V2)
            .user(objectMapper.writeValueAsString(evidence))
            .call()
            .entity(ReportNarrativeDraft.class);
    ReportValidator.ValidationResult validation = validator.validate(draft, evidence);
    return validation.valid()
            ? composer.assemble(snapshot, assessment, draft, modelName)
            : composer.fallback(snapshot, assessment, String.join(",", validation.issues()));
} catch (RuntimeException exception) {
    return composer.fallback(snapshot, assessment, "model unavailable");
}
```

Do not call `.tools(tools)` in fixed report generation. The system prompt treats all evidence text as untrusted data, prohibits changing direction, unsupported facts and trading instructions, and requires evidence IDs for key claims. `ReportValidator` extracts all numbers and bracketed evidence IDs, checks them against the package, requires deterministic conflicts to appear, and rejects forbidden trading/guarantee phrases.

Define the validation result as an immutable nested record:

```java
public record ValidationResult(boolean valid, List<String> issues) {
    public ValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
```

- [ ] **Step 4: Run agent, validator and application-context tests**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ReportValidatorTest,StockAnalysisAgentTest,AStockAgentApplicationTest' test
```

Expected: model-assisted and fallback branches pass; context starts with `spring.ai.model.chat=none`.

- [ ] **Step 5: Commit model boundary integration**

```powershell
git add src/main/java/com/astock/agent/agent src/test/java/com/astock/agent/agent
git commit -m "feat: validate model-assisted report narratives"
```

---

### Task 7: API contract and full frontend report view

**Files:**
- Modify: `src/main/java/com/astock/agent/web/AgentController.java`
- Modify: `src/test/java/com/astock/agent/web/AgentControllerTest.java`
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/main/resources/static/js/views.js`
- Modify: `src/main/resources/static/css/app.css`
- Modify: `tests/ui/dashboard.spec.js`

- [ ] **Step 1: Write failing API and UI assertions**

Add a MockMvc test that stubs `StockAnalysisAgent` and asserts:

```java
mvc.perform(post("/api/agent/analyze")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"600519\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.direction").value("STRONGER"))
        .andExpect(jsonPath("$.evidenceStatus").value("PARTIAL"))
        .andExpect(jsonPath("$.generationMode").value("DETERMINISTIC_FALLBACK"))
        .andExpect(jsonPath("$.conflicts").isArray())
        .andExpect(jsonPath("$.invalidationConditions").isArray())
        .andExpect(jsonPath("$.disclaimer").value("仅供学习研究，不构成投资建议"));
```

Update Playwright's mocked report to include every report section and assert headings for 核心驱动、技术与资金、基本面与预期、估值与行业、催化剂、风险与冲突、失效条件 and 来源.

- [ ] **Step 2: Run API and UI tests and verify RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=AgentControllerTest' test
npm.cmd run test:ui
```

Expected: API type/assertions or missing report headings fail.

- [ ] **Step 3: Render every report section safely**

Keep `stockApi.analyze(code)` unchanged. Replace the current compact `output.innerHTML` template with focused rendering helpers:

```javascript
function renderList(title, items, emptyText) {
  const rows = Array.isArray(items) ? items : [];
  return `<section class="report-section"><h4>${escapeText(title)}</h4>${rows.length
    ? `<ul>${rows.map((item) => `<li>${escapeText(typeof item === "string" ? item : item.interpretation)}</li>`).join("")}</ul>`
    : `<p class="report-empty">${escapeText(emptyText)}</p>`}</section>`;
}
```

Render direction and evidence status as text labels. Show source provider, observed time and link near evidence and repeat the complete source list at the bottom. Use `rel="noopener noreferrer"` for external links. Do not render HTML received from the API.

CSS uses the approved semantic tokens, unframed full-width report bands, no gradients or nested cards. At desktop use a two-column report detail grid; collapse to one column below 768px. Long URLs and Chinese text use `overflow-wrap:anywhere`.

- [ ] **Step 4: Run focused backend and UI tests**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=AgentControllerTest,StaticResourceTest' test
npm.cmd run test:ui
```

Expected: all tests pass at 1440x1000, 1024x768, 768x1024 and 390x844 with no horizontal overflow.

- [ ] **Step 5: Commit API and UI report**

```powershell
git add src/main/java/com/astock/agent/web/AgentController.java src/test/java/com/astock/agent/web/AgentControllerTest.java src/main/resources/static tests/ui/dashboard.spec.js
git commit -m "feat: present full institutional agent reports"
```

---

### Task 8: Documentation, regression verification and audits

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture/data-flow.md`
- Modify: `docs/data-sources/provider-matrix.md`
- Create: `docs/verification/institutional-report-verification.md`

- [ ] **Step 1: Update documentation**

Document:

- report direction is deterministic and internal scores are not public;
- evidence status is not a confidence probability;
- industry valuation and consensus forecast sources/caches;
- fixed reports do not use dynamic Tool Calling;
- model failure returns `DETERMINISTIC_FALLBACK`;
- report keeps `仅供学习研究，不构成投资建议`.

- [ ] **Step 2: Run full offline tests from a clean build**

```powershell
$env:JAVA_HOME=(Resolve-Path '.tools/jdk-21').Path
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
```

Expected: all default tests pass and no test accesses public market-data or model endpoints.

- [ ] **Step 3: Package and run repository verification**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-DskipTests' package
.\scripts\verify-data.cmd
npm.cmd run test:ui
```

Expected: executable JAR builds, data fixtures validate, and UI tests pass.

- [ ] **Step 4: Audit diff, secrets and generated evidence**

```powershell
git diff --check
rg -n -i "api[-_]?key|authorization|bearer|cookie|token|secret" src docs config --glob '!config/application-local.yml'
git status --short
```

Expected: no whitespace errors; only placeholder configuration mentions are present; `config/application-local.yml`, `.m2`, `.tools`, `target`, screenshots and user `out/` are not staged. Inspect generated report JSON and UI screenshots rather than relying only on exit codes.

- [ ] **Step 5: Commit docs and verification record**

```powershell
git add README.md docs/architecture/data-flow.md docs/data-sources/provider-matrix.md docs/verification/institutional-report-verification.md
git commit -m "docs: document institutional report evidence flow"
```

---

## Completion Criteria

- `POST /api/agent/analyze` returns the full institutional report contract.
- Direction is deterministic and is never changed by the model.
- No confidence percentage or internal score appears in API/UI output.
- Industry valuation and consensus forecasts preserve provenance and missing states.
- Model output is evidence-bound, validated and safely downgraded.
- Fixed report generation performs no dynamic tool calls.
- Frontend displays every report section and source without overflow.
- Default test suite remains offline; external checks stay tagged `external`.
- Full Maven tests, package, data verification, UI tests, diff check and secret audit pass.
