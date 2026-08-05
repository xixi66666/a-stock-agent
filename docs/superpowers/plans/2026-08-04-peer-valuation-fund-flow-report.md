# Peer Valuation and Fund Flow Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不新增外部数据源的前提下，把确定性同行估值比较和最新日、近 5 日、近 20 日分类资金流汇总统一提供给快照、总体报告、研究报告和数据页面。

**Architecture:** Provider 继续返回原始同行和资金历史；分析层新增纯计算记录与计算器，并通过 `DataSection` 传播状态和来源；报告层从同一快照复制结构化数据，模型只负责叙述；前端用共享渲染模块在估值、资金和两类报告中展示相同数据。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring AI、Jackson、AssertJ/JUnit 5、原生 ES Modules、Playwright。

---

## File Structure

### New Java files

- `src/main/java/com/astock/agent/marketdata/model/PeerSelectionReason.java`：稳定的同行筛选标签。
- `src/main/java/com/astock/agent/marketdata/model/IndustryPeerComparison.java`：具名同行及相对估值结果。
- `src/main/java/com/astock/agent/analysis/institutional/IndustryValuationCalculation.java`：行业计算数据和局部降级问题。
- `src/main/java/com/astock/agent/marketdata/model/FundFlowWindowSummary.java`：一个时间窗口的分类资金流累计。
- `src/main/java/com/astock/agent/marketdata/model/FundFlowSummary.java`：最新日、5 日、20 日汇总。
- `src/main/java/com/astock/agent/analysis/FundFlowSummaryCalculator.java`：纯确定性资金汇总。
- `src/test/java/com/astock/agent/analysis/FundFlowSummaryCalculatorTest.java`：资金汇总单元测试。
- `src/main/resources/static/js/derived-market-view.js`：同行和资金结构化区块的共享安全渲染。

### Modified Java files

- `src/main/java/com/astock/agent/marketdata/model/IndustryValuationData.java`
- `src/main/java/com/astock/agent/analysis/institutional/IndustryValuationCalculator.java`
- `src/main/java/com/astock/agent/analysis/institutional/IndustryValuationService.java`
- `src/main/java/com/astock/agent/analysis/StockResearchSnapshot.java`
- `src/main/java/com/astock/agent/analysis/ResearchAggregationService.java`
- `src/main/java/com/astock/agent/config/MarketDataConfiguration.java`
- `src/main/java/com/astock/agent/web/StockController.java`
- `src/main/java/com/astock/agent/analysis/institutional/ModuleAnalysisFactory.java`
- `src/main/java/com/astock/agent/agent/report/TechnicalAndFlowAnalysis.java`
- `src/main/java/com/astock/agent/agent/report/ValuationIndustryAnalysis.java`
- `src/main/java/com/astock/agent/agent/report/InstitutionalReportComposer.java`
- `src/main/java/com/astock/agent/agent/overall/OverallResearchReport.java`
- `src/main/java/com/astock/agent/agent/overall/OverallReportService.java`

### Modified tests and frontend files

- `src/test/java/com/astock/agent/analysis/institutional/IndustryValuationCalculatorTest.java`
- `src/test/java/com/astock/agent/analysis/institutional/IndustryValuationServiceTest.java`
- `src/test/java/com/astock/agent/analysis/ResearchAggregationServiceTest.java`
- `src/test/java/com/astock/agent/agent/report/InstitutionalReportComposerTest.java`
- `src/test/java/com/astock/agent/agent/overall/OverallResearchReportTest.java`
- `src/test/java/com/astock/agent/agent/overall/OverallReportServiceTest.java`
- `src/test/java/com/astock/agent/web/StockControllerTest.java`
- `src/test/java/com/astock/agent/web/AgentControllerTest.java`
- `src/test/java/com/astock/agent/web/StaticResourceTest.java`
- `src/main/resources/static/js/views.js`
- `src/main/resources/static/js/app.js`
- `src/main/resources/static/styles.css`
- `tests/ui/fixtures/partial-snapshot.json`
- `tests/ui/dashboard.spec.js`
- `README.md`

---

### Task 1: Select and Preserve Comparable Industry Peers

**Files:**
- Create: `src/main/java/com/astock/agent/marketdata/model/PeerSelectionReason.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/IndustryPeerComparison.java`
- Create: `src/main/java/com/astock/agent/analysis/institutional/IndustryValuationCalculation.java`
- Modify: `src/main/java/com/astock/agent/marketdata/model/IndustryValuationData.java`
- Modify: `src/main/java/com/astock/agent/analysis/institutional/IndustryValuationCalculator.java`
- Modify: `src/main/java/com/astock/agent/analysis/institutional/IndustryValuationService.java`
- Test: `src/test/java/com/astock/agent/analysis/institutional/IndustryValuationCalculatorTest.java`
- Test: `src/test/java/com/astock/agent/analysis/institutional/IndustryValuationServiceTest.java`

- [ ] **Step 1: Write failing peer-selection tests**

Extend `IndustryValuationCalculatorTest` with helpers that accept market value and add these tests:

```java
@Test
void selectsFiveNearestAndThreeIndustryLeadersWithStableOrder() {
    List<IndustryPeerQuote> peers = List.of(
            peer("600519", "30", "3", "100"),
            peer("N001", "20", "2", "99"),
            peer("N002", "21", "2.1", "101"),
            peer("N003", "22", "2.2", "95"),
            peer("N004", "23", "2.3", "105"),
            peer("N005", "24", "2.4", "90"),
            peer("L001", "25", "2.5", "1000"),
            peer("L002", "26", "2.6", "900"),
            peer("L003", "27", "2.7", "800"));

    IndustryValuationCalculation calculation = new IndustryValuationCalculator()
            .calculate("BK0477", "白酒", "600519", peers);

    assertThat(calculation.data().selectedPeers())
            .extracting(IndustryPeerComparison::code)
            .containsExactly("N001", "N002", "N003", "N004", "N005", "L001", "L002", "L003");
    assertThat(calculation.data().selectedPeers()).noneMatch(peer -> peer.code().equals("600519"));
    assertThat(calculation.issues()).isEmpty();
}

@Test
void mergesSelectionReasonsAndDoesNotInventInvalidPremiums() {
    List<IndustryPeerQuote> peers = List.of(
            peer("600519", "30", "3", "100"),
            peer("BOTH", "15", null, "99"),
            peer("A", "10", "1", "80"),
            peer("B", "11", "1.1", "70"),
            peer("C", "12", "1.2", "60"),
            peer("D", "13", "1.3", "50"),
            peer("E", "14", "1.4", "40"));

    IndustryPeerComparison both = new IndustryValuationCalculator()
            .calculate("BK0477", "白酒", "600519", peers)
            .data().selectedPeers().stream()
            .filter(peer -> peer.code().equals("BOTH"))
            .findFirst().orElseThrow();

    assertThat(both.selectionReasons()).containsExactly(
            PeerSelectionReason.MARKET_CAP_NEARBY,
            PeerSelectionReason.INDUSTRY_LEADER);
    assertThat(both.targetPePremiumPercent()).isEqualByComparingTo("100");
    assertThat(both.targetPbPremiumPercent()).isNull();
}

@Test
void excludesInvalidMarketValuesAndDoesNotPadAShortIndustry() {
    List<IndustryPeerQuote> peers = List.of(
            peer("600519", "30", "3", "100"),
            peer("VALID", "20", "2", "90"),
            peer("ZERO", "10", "1", "0"),
            peer("NEGATIVE", "10", "1", "-5"));

    IndustryValuationCalculation calculation = new IndustryValuationCalculator()
            .calculate("BK0477", "白酒", "600519", peers);

    assertThat(calculation.data().selectedPeers())
            .extracting(IndustryPeerComparison::code)
            .containsExactly("VALID");
}
```

Change the existing tests to read `calculation.data()` and replace the helper with:

```java
private static IndustryPeerQuote peer(String code, String pe, String pb, String marketValue) {
    return new IndustryPeerQuote(
            code, code,
            pe == null ? null : new BigDecimal(pe),
            pb == null ? null : new BigDecimal(pb),
            marketValue == null ? null : new BigDecimal(marketValue));
}
```

- [ ] **Step 2: Run the calculator test and verify RED**

Run:

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=IndustryValuationCalculatorTest' test
```

Expected: compilation fails because `IndustryValuationCalculation`, `IndustryPeerComparison`, `PeerSelectionReason`, and `selectedPeers` do not exist.

- [ ] **Step 3: Add immutable comparison records**

Create `PeerSelectionReason.java`:

```java
package com.astock.agent.marketdata.model;

public enum PeerSelectionReason {
    MARKET_CAP_NEARBY,
    INDUSTRY_LEADER
}
```

Create `IndustryPeerComparison.java`:

```java
package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.util.List;

public record IndustryPeerComparison(
        String code,
        String name,
        BigDecimal peDynamic,
        BigDecimal pb,
        BigDecimal totalMarketValueYuan,
        BigDecimal targetPePremiumPercent,
        BigDecimal targetPbPremiumPercent,
        List<PeerSelectionReason> selectionReasons) {

    public IndustryPeerComparison {
        selectionReasons = selectionReasons == null ? List.of() : List.copyOf(selectionReasons);
    }
}
```

Create `IndustryValuationCalculation.java`:

```java
package com.astock.agent.analysis.institutional;

import com.astock.agent.marketdata.model.IndustryValuationData;
import java.util.List;

public record IndustryValuationCalculation(
        IndustryValuationData data,
        List<String> issues) {

    public IndustryValuationCalculation {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
```

Append `List<IndustryPeerComparison> selectedPeers` to `IndustryValuationData` and normalize it with `List.copyOf` in the compact constructor. Add a backward-compatible 13-argument constructor that delegates with `List.of()` so unrelated fixture construction remains source compatible.

- [ ] **Step 4: Implement deterministic selection and premiums**

Change `IndustryValuationCalculator.calculate` to return `IndustryValuationCalculation`. Preserve the existing median and percentile calculation, then use these exact ordering rules:

```java
IndustryPeerQuote target = safePeers.stream()
        .filter(peer -> targetCode.equals(peer.code()))
        .findFirst().orElse(null);
BigDecimal targetMarketValue = positiveValue(target == null ? null : target.totalMarketValueYuan());
List<IndustryPeerQuote> eligible = safePeers.stream()
        .filter(peer -> peer != null && peer.code() != null && !peer.code().isBlank())
        .filter(peer -> !targetCode.equals(peer.code()))
        .filter(peer -> positiveValue(peer.totalMarketValueYuan()) != null)
        .toList();

List<IndustryPeerQuote> nearby = targetMarketValue == null ? List.of() : eligible.stream()
        .sorted(Comparator
                .comparing((IndustryPeerQuote peer) -> peer.totalMarketValueYuan()
                        .subtract(targetMarketValue).abs())
                .thenComparing(IndustryPeerQuote::code))
        .limit(5)
        .toList();
List<IndustryPeerQuote> leaders = eligible.stream()
        .sorted(Comparator.comparing(IndustryPeerQuote::totalMarketValueYuan)
                .reversed().thenComparing(IndustryPeerQuote::code))
        .limit(3)
        .toList();
```

Merge with a `LinkedHashMap<String, EnumSet<PeerSelectionReason>>`, inserting nearby first and leaders second. Convert each entry to `IndustryPeerComparison`. Calculate a premium only when both values are positive:

```java
private static BigDecimal premium(BigDecimal target, BigDecimal peer) {
    if (positiveValue(target) == null || positiveValue(peer) == null) return null;
    return target.divide(peer, 10, RoundingMode.HALF_UP)
            .subtract(BigDecimal.ONE)
            .multiply(BigDecimal.valueOf(100))
            .setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros();
}
```

Return issue `Target market value is unavailable; nearby peers were omitted` when `targetMarketValue` is null.

- [ ] **Step 5: Make the service propagate comparison degradation**

In `IndustryValuationService.compare`, receive `IndustryValuationCalculation calculation` and pass both data and issues into a revised `sectionLike` helper. Merge Provider issues with calculation issues. When the source status is `HEALTHY` but calculation issues are non-empty, return `DataSection.degraded`; preserve `STALE` and `UNVERIFIED` statuses for those source states.

Add this failing-then-green service test:

```java
@Test
void missingTargetMarketValueReturnsLeadersAsDegradedData() {
    SecurityId security = SecurityId.parse("600519");
    Sector sector = new Sector("白酒", "BK0477", BigDecimal.ONE, "600519");
    Provenance source = source("https://example.test/industry-peers");
    IndustryValuationService service = new IndustryValuationService(
            ignored -> DataSection.healthy(List.of(sector), source("https://example.test/sectors")),
            ignored -> DataSection.healthy(List.of(
                    peer("600519", "30", "3", null),
                    peer("000858", "20", "2", "900"),
                    peer("000568", "21", "2.1", "800")), source),
            new IndustryValuationCalculator(), Caffeine.newBuilder().build(), Caffeine.newBuilder().build());

    DataSection<IndustryValuationData> result = service.compare(security);

    assertThat(result.status()).isEqualTo(SectionStatus.DEGRADED);
    assertThat(result.payload().orElseThrow().selectedPeers())
            .extracting(IndustryPeerComparison::code)
            .containsExactly("000858", "000568");
    assertThat(result.issues()).contains("Target market value is unavailable; nearby peers were omitted");
}
```

Replace the existing service-test helper and update its current calls to pass a market value explicitly:

```java
private static IndustryPeerQuote peer(String code, String pe, String pb, String marketValue) {
    return new IndustryPeerQuote(
            code, code,
            pe == null ? null : new BigDecimal(pe),
            pb == null ? null : new BigDecimal(pb),
            marketValue == null ? null : new BigDecimal(marketValue));
}
```

- [ ] **Step 6: Run focused tests and commit**

Run the calculator and service tests. Expected: all pass.

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=IndustryValuationCalculatorTest,IndustryValuationServiceTest' test
git add src/main/java/com/astock/agent/marketdata/model src/main/java/com/astock/agent/analysis/institutional/IndustryValuationCalculation.java src/main/java/com/astock/agent/analysis/institutional/IndustryValuationCalculator.java src/main/java/com/astock/agent/analysis/institutional/IndustryValuationService.java src/test/java/com/astock/agent/analysis/institutional
git commit -m "feat: preserve comparable industry peers"
```

---

### Task 2: Derive Fund Flow Summaries in the Normalized Snapshot

**Files:**
- Create: `src/main/java/com/astock/agent/marketdata/model/FundFlowWindowSummary.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/FundFlowSummary.java`
- Create: `src/main/java/com/astock/agent/analysis/FundFlowSummaryCalculator.java`
- Create: `src/test/java/com/astock/agent/analysis/FundFlowSummaryCalculatorTest.java`
- Modify: `src/main/java/com/astock/agent/analysis/StockResearchSnapshot.java`
- Modify: `src/main/java/com/astock/agent/analysis/ResearchAggregationService.java`
- Modify: `src/main/java/com/astock/agent/config/MarketDataConfiguration.java`
- Modify: `src/main/java/com/astock/agent/web/StockController.java`
- Test: `src/test/java/com/astock/agent/analysis/ResearchAggregationServiceTest.java`
- Test: `src/test/java/com/astock/agent/web/StockControllerTest.java`

- [ ] **Step 1: Write failing calculator tests**

Create `FundFlowSummaryCalculatorTest.java` with these behaviors:

```java
package com.astock.agent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.FundFlow;
import com.astock.agent.marketdata.model.FundFlowSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FundFlowSummaryCalculatorTest {

    @Test
    void sortsDatesAndBuildsLatestFiveAndTwentyDayWindows() {
        List<FundFlow> values = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(day -> flow(LocalDate.of(2026, 7, day), day))
                .sorted(java.util.Comparator.comparing(FundFlow::date).reversed())
                .toList();
        List<FundFlow> scrambled = java.util.stream.Stream.concat(
                values.stream().filter(value -> value.date().getDayOfMonth() % 2 == 0),
                values.stream().filter(value -> value.date().getDayOfMonth() % 2 != 0)).toList();

        FundFlowSummary result = new FundFlowSummaryCalculator().calculate(scrambled);

        assertThat(result.latestDate()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(result.latestDay().sampleDays()).isEqualTo(1);
        assertThat(result.latestDay().mainNetYuan()).isEqualByComparingTo("20");
        assertThat(result.fiveDay().sampleDays()).isEqualTo(5);
        assertThat(result.fiveDay().mainNetYuan()).isEqualByComparingTo("90");
        assertThat(result.fiveDay().superLargeNetYuan()).isEqualByComparingTo("450");
        assertThat(result.fiveDay().largeNetYuan()).isEqualByComparingTo("360");
        assertThat(result.fiveDay().mediumNetYuan()).isEqualByComparingTo("270");
        assertThat(result.fiveDay().smallNetYuan()).isEqualByComparingTo("180");
        assertThat(result.twentyDay().sampleDays()).isEqualTo(20);
        assertThat(result.twentyDay().mainNetYuan()).isEqualByComparingTo("210");
        assertThat(result.twentyDay().superLargeNetYuan()).isEqualByComparingTo("1050");
        assertThat(result.twentyDay().largeNetYuan()).isEqualByComparingTo("840");
        assertThat(result.twentyDay().mediumNetYuan()).isEqualByComparingTo("630");
        assertThat(result.twentyDay().smallNetYuan()).isEqualByComparingTo("420");
    }

    @Test
    void shortWindowReportsActualDaysAndMissingCategoryAsNull() {
        FundFlow first = flow(LocalDate.of(2026, 7, 2), 2);
        FundFlow missingMedium = new FundFlow(LocalDate.of(2026, 7, 1),
                bd(1), bd(2), null, bd(4), bd(5), "fixture");

        FundFlowSummary result = new FundFlowSummaryCalculator().calculate(List.of(missingMedium, first));

        assertThat(result.fiveDay().sampleDays()).isEqualTo(2);
        assertThat(result.fiveDay().mainNetYuan()).isEqualByComparingTo("3");
        assertThat(result.fiveDay().mediumNetYuan()).isNull();
    }

    @Test
    void duplicateDateKeepsTheFirstRecordDeterministically() {
        LocalDate date = LocalDate.of(2026, 7, 15);

        FundFlowSummary result = new FundFlowSummaryCalculator().calculate(List.of(
                flow(date, 7), flow(date, 99), flow(date.minusDays(1), 3)));

        assertThat(result.latestDay().mainNetYuan()).isEqualByComparingTo("7");
        assertThat(result.fiveDay().sampleDays()).isEqualTo(2);
        assertThat(result.fiveDay().mainNetYuan()).isEqualByComparingTo("10");
    }

    @Test
    void emptyInputProducesExplicitZeroSampleWindowsWithoutAmounts() {
        FundFlowSummary result = new FundFlowSummaryCalculator().calculate(List.of());

        assertThat(result.latestDate()).isNull();
        assertThat(result.latestDay().sampleDays()).isZero();
        assertThat(result.fiveDay().sampleDays()).isZero();
        assertThat(result.twentyDay().sampleDays()).isZero();
        assertThat(result.twentyDay().mainNetYuan()).isNull();
    }

    private static FundFlow flow(LocalDate date, int value) {
        return new FundFlow(date, bd(value), bd(value * 2L), bd(value * 3L),
                bd(value * 4L), bd(value * 5L), "fixture");
    }

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run `FundFlowSummaryCalculatorTest`. Expected: compilation fails because the summary records and calculator do not exist.

- [ ] **Step 3: Add summary records and calculator**

Create `FundFlowWindowSummary.java`:

```java
package com.astock.agent.marketdata.model;

import java.math.BigDecimal;

public record FundFlowWindowSummary(
        int requestedDays,
        int sampleDays,
        BigDecimal mainNetYuan,
        BigDecimal superLargeNetYuan,
        BigDecimal largeNetYuan,
        BigDecimal mediumNetYuan,
        BigDecimal smallNetYuan) {
}
```

Create `FundFlowSummary.java`:

```java
package com.astock.agent.marketdata.model;

import java.time.LocalDate;

public record FundFlowSummary(
        LocalDate latestDate,
        FundFlowWindowSummary latestDay,
        FundFlowWindowSummary fiveDay,
        FundFlowWindowSummary twentyDay) {
}
```

Implement `FundFlowSummaryCalculator` as a stateless class. Filter null rows and null dates, use a reverse-order `TreeMap<LocalDate, FundFlow>` with `putIfAbsent` to deterministically keep the first row per date, then calculate windows 1, 5 and 20. For each category return `null` if any row in that window has a null value:

```java
private static BigDecimal sumComplete(
        List<FundFlow> values,
        java.util.function.Function<FundFlow, BigDecimal> getter) {
    if (values.isEmpty() || values.stream().anyMatch(value -> getter.apply(value) == null)) return null;
    return values.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
}
```

For an empty input, return a summary with `latestDate=null` and three windows whose `sampleDays` are zero and amounts are null.

- [ ] **Step 4: Add a failing aggregation propagation test**

Extend `ResearchAggregationServiceTest.StubGateway` so a subclass can return:

```java
@Override
public DataSection<?> fundFlow(SecurityId security) {
    Provenance fallback = new Provenance("Sina", URI.create("https://example.com/fund-flow"), null,
            Instant.parse("2026-07-15T08:00:00Z"), false, "Eastmoney");
    return DataSection.degraded(List.of(
            new FundFlow(LocalDate.of(2026, 7, 15), bd(10), bd(1), bd(2), bd(3), bd(4), "Sina")),
            fallback, List.of("Eastmoney unavailable; using Sina daily fund-flow fallback"));
}
```

Assert:

```java
assertThat(result.fundFlowSummary().status()).isEqualTo(SectionStatus.DEGRADED);
assertThat(result.fundFlowSummary().payload().orElseThrow().latestDay().mainNetYuan())
        .isEqualByComparingTo("10");
assertThat(result.fundFlowSummary().provenance().orElseThrow().fallbackProvider())
        .isEqualTo("Eastmoney");
```

Add a second aggregation test that invokes the service once with a sourced healthy empty list and once with `DataSection.unavailable("provider failed")`:

```java
assertThat(emptyResult.fundFlowSummary().status()).isEqualTo(SectionStatus.DEGRADED);
assertThat(emptyResult.fundFlowSummary().payload().orElseThrow().latestDay().sampleDays()).isZero();
assertThat(emptyResult.fundFlowSummary().issues())
        .contains("Fund-flow provider returned an empty history");

assertThat(failedResult.fundFlowSummary().status()).isEqualTo(SectionStatus.UNAVAILABLE);
assertThat(failedResult.fundFlowSummary().payload()).isEmpty();
assertThat(failedResult.fundFlowSummary().issues()).contains("provider failed");
```

Add this failing API assertion to `StockControllerTest` so the source-only response cannot silently omit the two derived sections:

```java
@Test
void sourcesExposeDerivedValuationAndFundFlowSections() throws Exception {
    mvc.perform(get("/api/stocks/600519/sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.industryValuation.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.fundFlowSummary.status").value("UNAVAILABLE"));
}
```

Run both affected tests before implementation:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ResearchAggregationServiceTest,StockControllerTest' test
```

Expected: compilation fails because `fundFlowSummary()` and the calculator dependency do not exist; after the snapshot type exists but before the controller is changed, the source-response assertion remains RED.

- [ ] **Step 5: Integrate the derived section into aggregation**

Append `DataSection<FundFlowSummary> fundFlowSummary` immediately after raw `fundFlow` in `StockResearchSnapshot`. Add an overloaded constructor with the old parameter list that delegates using `DataSection.unavailable("fund flow summary not provided")`, then update `empty`, `copy`, and the production canonical constructor calls to preserve the new field.

Inject `FundFlowSummaryCalculator` into `ResearchAggregationService`. After `flowFuture.get()`, derive the summary once and place both raw and derived sections into the snapshot. Add a `summarizeFlow` helper that:

1. returns unavailable with the original issue when raw flow is unavailable;
2. rejects a non-list payload as unavailable;
3. filters `FundFlow` records and calculates the summary;
4. turns a sourced empty list into `DEGRADED` with issue `Fund-flow provider returned an empty history`;
5. otherwise mirrors `HEALTHY`, `DEGRADED`, `STALE`, or `UNVERIFIED` with the same provenance and issues.

Register `FundFlowSummaryCalculator` as a bean in `MarketDataConfiguration` and pass it into `ResearchAggregationService`. Add `industryValuation` and `fundFlowSummary` to `StockController.sources`.

- [ ] **Step 6: Run focused tests and commit**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=FundFlowSummaryCalculatorTest,ResearchAggregationServiceTest,StockControllerTest' test
git add src/main/java/com/astock/agent/marketdata/model/FundFlowSummary.java src/main/java/com/astock/agent/marketdata/model/FundFlowWindowSummary.java src/main/java/com/astock/agent/analysis/FundFlowSummaryCalculator.java src/main/java/com/astock/agent/analysis/StockResearchSnapshot.java src/main/java/com/astock/agent/analysis/ResearchAggregationService.java src/main/java/com/astock/agent/config/MarketDataConfiguration.java src/main/java/com/astock/agent/web/StockController.java src/test/java/com/astock/agent/analysis src/test/java/com/astock/agent/web/StockControllerTest.java
git commit -m "feat: derive normalized fund flow summaries"
```

Expected: focused tests pass and `out/` remains untracked.

---

### Task 3: Attach Deterministic Details to Both Report Contracts

**Files:**
- Modify: `src/main/java/com/astock/agent/analysis/institutional/ModuleAnalysisFactory.java`
- Modify: `src/main/java/com/astock/agent/agent/report/TechnicalAndFlowAnalysis.java`
- Modify: `src/main/java/com/astock/agent/agent/report/ValuationIndustryAnalysis.java`
- Modify: `src/main/java/com/astock/agent/agent/report/InstitutionalReportComposer.java`
- Modify: `src/main/java/com/astock/agent/agent/overall/OverallResearchReport.java`
- Modify: `src/main/java/com/astock/agent/agent/overall/OverallReportService.java`
- Test: `src/test/java/com/astock/agent/agent/report/InstitutionalReportComposerTest.java`
- Test: `src/test/java/com/astock/agent/agent/overall/OverallResearchReportTest.java`
- Test: `src/test/java/com/astock/agent/agent/overall/OverallReportServiceTest.java`
- Test: `src/test/java/com/astock/agent/web/AgentControllerTest.java`

- [ ] **Step 1: Write failing report-contract tests**

Build a snapshot fixture containing one selected peer and a non-empty `FundFlowSummary`. Add to `InstitutionalReportComposerTest`:

```java
@Test
void deterministicAndModelAssistedReportsPreserveStructuredMarketDetails() {
    StockResearchSnapshot snapshot = enrichedSnapshot();
    DeterministicAssessment assessment = new ResearchJudgementEngine().assess(snapshot);

    InstitutionalResearchReport fallback = composer.fallback(snapshot, assessment, "model unavailable");
    InstitutionalResearchReport assisted = composer.assemble(
            snapshot, assessment,
            new ReportNarrativeDraft("摘要", "技术", "基本面", "估值", List.of(), List.of()),
            "mimo-v2.5-pro");

    assertThat(fallback.technicalAndFlow().fundFlowSummary()).isEqualTo(snapshot.fundFlowSummary());
    assertThat(assisted.technicalAndFlow().fundFlowSummary()).isEqualTo(snapshot.fundFlowSummary());
    assertThat(fallback.valuationAndIndustry().industryValuation())
            .isEqualTo(snapshot.industryValuation());
    assertThat(assisted.valuationAndIndustry().industryValuation())
            .isEqualTo(snapshot.industryValuation());

    assertThat(fallback.technicalAndFlow().facts())
            .extracting(ReportFact::label)
            .contains("近5日主力净流入", "近5日超大单净流入", "近5日大单净流入",
                    "近5日中单净流入", "近5日小单净流入",
                    "近20日主力净流入", "近20日超大单净流入", "近20日大单净流入",
                    "近20日中单净流入", "近20日小单净流入");
    assertThat(fallback.valuationAndIndustry().facts())
            .anySatisfy(fact -> {
                assertThat(fact.label()).isEqualTo("同行估值·五粮液(000858)");
                assertThat(fact.value()).contains("PE 18", "PB 4", "总市值 700000000000 元", "市值接近");
            });

    ModuleAnalysis flowModule = assessment.moduleAnalysis(AnalysisModule.FUND_FLOW_CAPITAL);
    assertThat(flowModule.facts()).extracting(ReportFact::label)
            .contains("近5日超大单净流入", "近5日大单净流入", "近5日中单净流入",
                    "近5日小单净流入", "近20日超大单净流入", "近20日大单净流入",
                    "近20日中单净流入", "近20日小单净流入");
}
```

Implement the test helper with the new canonical snapshot constructor so the section values are explicit:

```java
private static StockResearchSnapshot enrichedSnapshot() {
    StockResearchSnapshot base = snapshot();
    Provenance source = new Provenance("fixture", URI.create("https://example.com/derived"), null,
            Instant.parse("2026-07-15T08:00:00Z"), false, null);
    IndustryPeerComparison peer = new IndustryPeerComparison(
            "000858", "五粮液", bd(18), bd(4), bd(700000000000L),
            bd(11.11), bd(25), List.of(PeerSelectionReason.MARKET_CAP_NEARBY));
    IndustryValuationData valuation = new IndustryValuationData(
            "BK0477", "白酒", 2, 2, 0, 2, 0,
            bd(20), bd(19), bd(100), bd(5), bd(4.5), bd(100), List.of(peer));
    FundFlowSummary summary = new FundFlowSummary(
            LocalDate.of(2026, 7, 15),
            new FundFlowWindowSummary(1, 1, bd(10), bd(4), bd(6), bd(-2), bd(-3)),
            new FundFlowWindowSummary(5, 5, bd(50), bd(20), bd(30), bd(-10), bd(-15)),
            new FundFlowWindowSummary(20, 18, bd(120), bd(70), bd(50), bd(-30), bd(-40)));
    return new StockResearchSnapshot(
            base.security(), base.quote(), base.bars(), base.technical(), base.sectors(),
            DataSection.healthy(valuation, source), base.fundFlow(), DataSection.healthy(summary, source),
            base.capital(), base.fundamentals(), base.research(), base.news(), base.announcements(),
            base.quality(), base.crossSourceConsistent(), base.coreCompleteness(),
            base.authoritativeSources(), base.fetchedAt());
}
```

Add the corresponding imports for `AnalysisModule`, `ModuleAnalysis`, `IndustryPeerComparison`, `IndustryValuationData`, `PeerSelectionReason`, `FundFlowSummary`, `FundFlowWindowSummary`, `List`, and `URI`.

Add to `OverallResearchReportTest`:

```java
@Test
void copiesStructuredMarketDetailsFromSnapshotInsteadOfDraft() {
    StockResearchSnapshot snapshot = enrichedSnapshot();
    OverallResearchReport report = OverallResearchReport.fromSnapshot(
            draft(), "mimo-v2.5-pro", snapshot, Instant.EPOCH, "overall-v3");

    assertThat(report.industryValuation()).isEqualTo(snapshot.industryValuation());
    assertThat(report.fundFlowSummary()).isEqualTo(snapshot.fundFlowSummary());
}
```

In `OverallResearchReportTest`, add these exact helpers; use the same valuation and summary values as the assertions:

```java
private static OverallReportDraft draft() {
    return new OverallReportDraft(
            "结论", "数据质量", "公司与基本面", "技术与资金", "估值与行业", "事件与情绪",
            List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), "仅供学习研究，不构成投资建议");
}

private static StockResearchSnapshot enrichedSnapshot() {
    SecurityId id = SecurityId.parse("600519");
    StockResearchSnapshot base = StockResearchSnapshot.empty(id);
    Provenance source = new Provenance("fixture", URI.create("https://example.com/derived"), null,
            Instant.EPOCH, false, null);
    IndustryPeerComparison peer = new IndustryPeerComparison(
            "000858", "五粮液", new BigDecimal("18"), new BigDecimal("4"),
            new BigDecimal("700000000000"), new BigDecimal("11.11"), new BigDecimal("25"),
            List.of(PeerSelectionReason.MARKET_CAP_NEARBY));
    IndustryValuationData valuation = new IndustryValuationData(
            "BK0477", "白酒", 2, 2, 0, 2, 0,
            new BigDecimal("20"), new BigDecimal("19"), new BigDecimal("100"),
            new BigDecimal("5"), new BigDecimal("4.5"), new BigDecimal("100"), List.of(peer));
    FundFlowSummary summary = new FundFlowSummary(
            LocalDate.of(2026, 7, 15),
            new FundFlowWindowSummary(1, 1, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                    BigDecimal.ONE.negate(), BigDecimal.ONE.negate()),
            new FundFlowWindowSummary(5, 5, new BigDecimal("50"), new BigDecimal("20"),
                    new BigDecimal("30"), new BigDecimal("-10"), new BigDecimal("-15")),
            new FundFlowWindowSummary(20, 18, new BigDecimal("120"), new BigDecimal("70"),
                    new BigDecimal("50"), new BigDecimal("-30"), new BigDecimal("-40")));
    return new StockResearchSnapshot(
            base.security(), base.quote(), base.bars(), base.technical(), base.sectors(),
            DataSection.healthy(valuation, source), base.fundFlow(), DataSection.healthy(summary, source),
            base.capital(), base.fundamentals(), base.research(), base.news(), base.announcements(),
            base.quality(), base.crossSourceConsistent(), base.coreCompleteness(),
            base.authoritativeSources(), base.fetchedAt());
}
```

- [ ] **Step 2: Run report tests and verify RED**

Run `InstitutionalReportComposerTest,OverallResearchReportTest`. Expected: compilation fails because report child records do not expose the new fields and `OverallResearchReport.fromSnapshot` does not exist.

- [ ] **Step 3: Extend report records with backward-compatible constructors**

Append `DataSection<FundFlowSummary> fundFlowSummary` to `TechnicalAndFlowAnalysis`. Keep the existing 2-, 3-, and 7-argument constructors and delegate with `DataSection.unavailable("fund flow summary not provided")`; add an 8-argument canonical call for the composer.

Append `DataSection<IndustryValuationData> industryValuation` to `ValuationIndustryAnalysis`. Keep the existing constructors by delegating with `DataSection.unavailable("industry valuation not provided")`.

Append `DataSection<IndustryValuationData> industryValuation` and `DataSection<FundFlowSummary> fundFlowSummary` to `OverallResearchReport` immediately before model metadata. Keeping `DataSection` preserves `status`, `issues` and `Provenance` in report responses. Add an overloaded constructor with the old field list that delegates with unavailable sections for controller tests and compatibility. Keep the existing `from(draft, modelName, snapshotAt, generatedAt, promptVersion)` factory for validator-test/source compatibility, and add the snapshot-aware `fromSnapshot` factory below. A same-name overload is intentionally avoided because legacy calls with a null third argument would be ambiguous between `Instant` and `StockResearchSnapshot`.

```java
public static OverallResearchReport fromSnapshot(
        OverallReportDraft draft,
        String modelName,
        StockResearchSnapshot snapshot,
        Instant generatedAt,
        String promptVersion) {
    Objects.requireNonNull(draft, "draft");
    Objects.requireNonNull(snapshot, "snapshot");
    return new OverallResearchReport(
            draft.overallConclusion(), draft.dataQualitySummary(),
            draft.companyAndFundamentals(), draft.technicalAndCapital(),
            draft.valuationAndIndustry(), draft.eventsAndSentiment(),
            draft.bullishEvidence(), draft.bearishEvidence(), draft.riskFactors(),
            draft.scenarios(), draft.conflictsAndMissingData(), draft.sourceReferences(),
            snapshot.industryValuation(), snapshot.fundFlowSummary(),
            modelName, snapshot.fetchedAt(), generatedAt, promptVersion, draft.disclaimer());
}
```

Normalize nullable section arguments in each compact constructor so Jackson/tests cannot create a report with an invalid null wrapper:

```java
fundFlowSummary = fundFlowSummary == null
        ? DataSection.unavailable("fund flow summary not provided")
        : fundFlowSummary;
industryValuation = industryValuation == null
        ? DataSection.unavailable("industry valuation not provided")
        : industryValuation;
```

Apply only the field relevant to each child record and both fields to `OverallResearchReport`.

- [ ] **Step 4: Populate structured report fields and facts**

In `OverallReportService`, call the new `from` overload with the same snapshot used by the generator and validator.

In `InstitutionalReportComposer.report`, read:

```java
DataSection<FundFlowSummary> flowSummary = snapshot.fundFlowSummary();
DataSection<IndustryValuationData> valuationData = snapshot.industryValuation();
```

Pass `flowSummary` to `TechnicalAndFlowAnalysis` and `valuationData` to `ValuationIndustryAnalysis` for fallback, partial, warning and fully model-assisted modes through the single shared `report` method.

Replace raw-history aggregation in `addFlowFacts` with the summary. Add facts for all five categories in the 5-day and 20-day windows, using exactly `近{窗口}日{主力|超大单|大单|中单|小单}净流入` and `元` units. Add one fact per selected peer with label `同行估值·{name}({code})`; build its value from only non-null segments `PE {value}`、`PB {value}`、`总市值 {value} 元` plus Chinese reason labels `市值接近` and `行业龙头`, so no `null` text enters the report.

Update `ModuleAnalysisFactory.flowAndCapital` to consume `snapshot.fundFlowSummary()` for its main 5/20-day facts and add the other four category totals with the same labels. Preserve the existing main-flow direction signal only when the 20-day main total is non-null, keep the short/long direction-conflict check only when both main totals are non-null, and preserve all capital signals.

- [ ] **Step 5: Run focused report tests and commit**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=InstitutionalReportComposerTest,OverallResearchReportTest,OverallReportServiceTest,AgentControllerTest,ModuleAnalysisFactoryTest' test
git add src/main/java/com/astock/agent/analysis/institutional/ModuleAnalysisFactory.java src/main/java/com/astock/agent/agent/report src/main/java/com/astock/agent/agent/overall src/test/java/com/astock/agent/agent src/test/java/com/astock/agent/web/AgentControllerTest.java
git commit -m "feat: attach market details to reports"
```

Expected: all focused tests pass; model narrative DTO remains unchanged and structured numeric fields come only from the snapshot.

---

### Task 4: Render Peer Valuation and Fund Flow on Data Pages

**Files:**
- Create: `src/main/resources/static/js/derived-market-view.js`
- Modify: `src/main/resources/static/js/views.js`
- Modify: `src/main/resources/static/styles.css`
- Modify: `tests/ui/fixtures/partial-snapshot.json`
- Modify: `tests/ui/dashboard.spec.js`
- Modify: `src/test/java/com/astock/agent/web/StaticResourceTest.java`

- [ ] **Step 1: Enrich the offline UI fixture**

Add this healthy `industryValuation` section containing target PE/PB, medians, percentiles and two selected peers. One peer contains both selection reasons:

```json
"industryValuation": {
  "status": "HEALTHY",
  "payload": {
    "industryCode": "BK0477",
    "industryName": "白酒",
    "totalSamples": 20,
    "validPeSamples": 18,
    "excludedPeSamples": 2,
    "validPbSamples": 19,
    "excludedPbSamples": 1,
    "targetPe": 20.32,
    "peMedian": 18.40,
    "pePercentile": 72.22,
    "targetPb": 7.46,
    "pbMedian": 4.80,
    "pbPercentile": 84.21,
    "selectedPeers": [
      {
        "code": "000858",
        "name": "五粮液",
        "peDynamic": 18.10,
        "pb": 4.20,
        "totalMarketValueYuan": 530000000000,
        "targetPePremiumPercent": 12.27,
        "targetPbPremiumPercent": 77.62,
        "selectionReasons": ["MARKET_CAP_NEARBY", "INDUSTRY_LEADER"]
      },
      {
        "code": "000568",
        "name": "泸州老窖",
        "peDynamic": 17.20,
        "pb": 4.60,
        "totalMarketValueYuan": 210000000000,
        "targetPePremiumPercent": 18.14,
        "targetPbPremiumPercent": 62.17,
        "selectionReasons": ["MARKET_CAP_NEARBY"]
      }
    ]
  },
  "provenance": {
    "provider": "Eastmoney",
    "sourceUrl": "https://push2.eastmoney.com/industry-peers",
    "providerTimestamp": null,
    "fetchedAt": "2026-07-15T07:00:08Z",
    "cached": false,
    "fallbackProvider": null
  },
  "issues": []
}
```

Add a healthy `fundFlowSummary` section with:

```json
{
  "status": "HEALTHY",
  "payload": {
    "latestDate": "2026-07-15",
    "latestDay": {
      "requestedDays": 1,
      "sampleDays": 1,
      "mainNetYuan": 386000000,
      "superLargeNetYuan": 231000000,
      "largeNetYuan": 155000000,
      "mediumNetYuan": -76000000,
      "smallNetYuan": -121000000
    },
    "fiveDay": {
      "requestedDays": 5,
      "sampleDays": 5,
      "mainNetYuan": 820000000,
      "superLargeNetYuan": 500000000,
      "largeNetYuan": 320000000,
      "mediumNetYuan": -180000000,
      "smallNetYuan": -260000000
    },
    "twentyDay": {
      "requestedDays": 20,
      "sampleDays": 18,
      "mainNetYuan": 1260000000,
      "superLargeNetYuan": 900000000,
      "largeNetYuan": 360000000,
      "mediumNetYuan": -430000000,
      "smallNetYuan": -610000000
    }
  },
  "provenance": {
    "provider": "Eastmoney",
    "sourceUrl": "https://push2his.eastmoney.com",
    "providerTimestamp": null,
    "fetchedAt": "2026-07-15T07:00:09Z",
    "cached": false,
    "fallbackProvider": null
  },
  "issues": []
}
```

- [ ] **Step 2: Write failing Playwright assertions**

Add two tests:

```javascript
test("valuation view shows target and deterministic peer groups", async ({ page }) => {
  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "估值预期" }).click();

  const table = page.getByRole("table", { name: "同行估值对比" });
  await expect(table).toContainText("目标");
  await expect(table).toContainText("市值接近");
  await expect(table).toContainText("行业龙头");
  await expect(table).toContainText("000858");
  await expect(table).toContainText("五粮液");
});

test("capital view shows latest and multi-window order-size flows", async ({ page }) => {
  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "资金筹码" }).click();

  await expect(page.locator("#view-content")).toContainText("最新日资金流");
  await expect(page.locator("#view-content")).toContainText("中单净流入");
  await expect(page.locator("#view-content")).toContainText("小单净流入");
  const table = page.getByRole("table", { name: "资金流窗口汇总" });
  await expect(table).toContainText("近 5 日");
  await expect(table).toContainText("近 20 日");
  await expect(table).toContainText("18 / 20 日");
});
```

- [ ] **Step 3: Run the two UI tests and verify RED**

Start the app with `start.ps1` using the repository JDK, set `PLAYWRIGHT_CHANNEL=msedge`, then run:

```powershell
$projectRoot = (Resolve-Path '.').Path
$existingListener = Get-NetTCPConnection -LocalPort 10001 -State Listen -ErrorAction SilentlyContinue
if ($existingListener) { throw 'Port 10001 is already in use; do not stop an unowned process.' }
$server = Start-Process powershell.exe -ArgumentList @(
  '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $projectRoot 'start.ps1')
) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru
try {
  for ($attempt = 1; $attempt -le 60; $attempt++) {
    try {
      Invoke-RestMethod 'http://127.0.0.1:10001/actuator/health' | Out-Null
      break
    } catch {
      if ($attempt -eq 60) { throw }
      Start-Sleep -Seconds 1
    }
  }
  $env:PLAYWRIGHT_CHANNEL='msedge'
  npm.cmd run test:ui -- --grep "deterministic peer groups|multi-window order-size flows"
} finally {
  $ownedListener = Get-NetTCPConnection -LocalPort 10001 -State Listen -ErrorAction SilentlyContinue
  if ($ownedListener) { Stop-Process -Id $ownedListener.OwningProcess -Force }
  if (!$server.HasExited) { Stop-Process -Id $server.Id -Force }
}
```

The preflight makes ownership explicit: abort when 10001 is already occupied, and clean up only the listener created after this plan starts the server.

Expected: both tests fail because the tables and labels are absent.

- [ ] **Step 4: Create the shared renderer**

Create `derived-market-view.js` with this implementation:

```javascript
const escapeHtml = (value) => String(value ?? "")
  .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
  .replaceAll('"', "&quot;").replaceAll("'", "&#039;");

const number = (value, digits = 2) => {
  if (value == null || value === "") return "--";
  const parsed = Number(value);
  return Number.isFinite(parsed)
    ? parsed.toLocaleString("zh-CN", { minimumFractionDigits: digits, maximumFractionDigits: digits })
    : "--";
};

const money = (value) => {
  if (value == null || value === "") return "--";
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return "--";
  if (Math.abs(parsed) >= 1e8) return `${number(parsed / 1e8)} 亿`;
  if (Math.abs(parsed) >= 1e4) return `${number(parsed / 1e4)} 万`;
  return `${number(parsed, 0)} 元`;
};

const percent = (value) => value == null || value === "" ? "--" : `${number(value)}%`;
const reasonLabel = (reason) => ({
  MARKET_CAP_NEARBY: "市值接近",
  INDUSTRY_LEADER: "行业龙头",
})[reason] || reason;

function unavailable(title, section) {
  const issue = section?.issues?.[0] || "数据不可用";
  return `<section class="derived-market-block"><h4>${escapeHtml(title)}</h4><p class="muted">${escapeHtml(issue)}</p></section>`;
}

export function renderPeerValuationTable(section, target = {}) {
  const data = section?.payload;
  if (!data) return section ? unavailable("同行估值对比", section) : "";
  const targetRow = {
    code: target.code || "--",
    name: target.name || "目标股票",
    peDynamic: target.peTtm ?? data.targetPe,
    pb: target.pb ?? data.targetPb,
    totalMarketValueYuan: target.totalMarketValueYuan,
    selectionReasons: ["TARGET"],
  };
  const rows = [targetRow, ...(Array.isArray(data.selectedPeers) ? data.selectedPeers : [])];
  const body = rows.map((peer, index) => {
    const tags = index === 0
      ? '<span class="peer-selection-tag">目标</span>'
      : (peer.selectionReasons || []).map((reason) =>
          `<span class="peer-selection-tag">${escapeHtml(reasonLabel(reason))}</span>`).join("");
    return `<tr class="${index === 0 ? "target-row" : ""}"><td>${escapeHtml(peer.code || "--")}</td>`
      + `<td>${escapeHtml(peer.name || "--")}</td><td><span class="peer-selection-tags">${tags}</span></td>`
      + `<td>${number(peer.peDynamic)}</td><td>${number(peer.pb)}</td><td>${money(peer.totalMarketValueYuan)}</td>`
      + `<td>${index === 0 ? "--" : percent(peer.targetPePremiumPercent)}</td>`
      + `<td>${index === 0 ? "--" : percent(peer.targetPbPremiumPercent)}</td></tr>`;
  }).join("");
  return `<section class="derived-market-block"><h4>同行估值对比</h4>`
    + `<div class="table-scroll"><table aria-label="同行估值对比"><thead><tr>`
    + `<th>代码</th><th>名称</th><th>筛选</th><th>动态 PE</th><th>PB</th><th>总市值</th>`
    + `<th>目标 PE 溢价</th><th>目标 PB 溢价</th></tr></thead><tbody>${body}</tbody></table></div></section>`;
}

export function renderFundFlowSummary(section) {
  const summary = section?.payload;
  if (!summary) return section ? unavailable("资金流窗口汇总", section) : "";
  const rows = [["近 5 日", summary.fiveDay], ["近 20 日", summary.twentyDay]]
    .map(([label, window]) => `<tr><td>${label}</td>`
      + `<td>${money(window?.mainNetYuan)}</td><td>${money(window?.superLargeNetYuan)}</td>`
      + `<td>${money(window?.largeNetYuan)}</td><td>${money(window?.mediumNetYuan)}</td>`
      + `<td>${money(window?.smallNetYuan)}</td>`
      + `<td>${window ? `${window.sampleDays} / ${window.requestedDays} 日` : "--"}</td></tr>`).join("");
  return `<section class="derived-market-block"><h4>资金流窗口汇总</h4>`
    + `<div class="table-scroll"><table class="flow-window-table" aria-label="资金流窗口汇总">`
    + `<thead><tr><th>窗口</th><th>主力</th><th>超大单</th><th>大单</th><th>中单</th><th>小单</th><th>样本</th></tr></thead>`
    + `<tbody>${rows}</tbody></table></div></section>`;
}
```

- [ ] **Step 5: Integrate data pages and styles**

Import both shared renderers in `views.js`. Change `renderCapital` to use `snapshot.fundFlowSummary.payload`, render five separate latest-day cards, and append the shared window table. Keep existing capital sections.

Use these exact integration shapes:

```javascript
import { renderFundFlowSummary, renderPeerValuationTable } from "./derived-market-view.js";

const flowSummary = sectionPayload(snapshot.fundFlowSummary);
const latest = flowSummary?.latestDay || {};
const metrics = [
  ["主力净流入", money(latest.mainNetYuan), latest.mainNetYuan],
  ["超大单净流入", money(latest.superLargeNetYuan), latest.superLargeNetYuan],
  ["大单净流入", money(latest.largeNetYuan), latest.largeNetYuan],
  ["中单净流入", money(latest.mediumNetYuan), latest.mediumNetYuan],
  ["小单净流入", money(latest.smallNetYuan), latest.smallNetYuan],
];
```

Change the empty-view guard to `if (!flowSummary && !capital)`, use `flowSummary?.latestDate` in the heading, add `<h3>最新日资金流</h3>` immediately before the metric grid, and append `renderFundFlowSummary(snapshot.fundFlowSummary)` immediately after it. Keep raw `snapshot.fundFlow` only as source/history data rather than recomputing totals in the browser.

Change `renderValuation` to read `snapshot.industryValuation`, show the existing metrics and append the shared peer table before institution reports. Use the quote name/code/PE/PB/market value for the pinned target row.

```javascript
const target = {
  code: snapshot.security?.code,
  name: quote.name,
  peTtm: quote.peTtm,
  pb: quote.pb,
  totalMarketValueYuan: quote.totalMarketValueYuan,
};
const peerTable = renderPeerValuationTable(snapshot.industryValuation, target);
```

Add these compact styles; reuse the existing table rules and semantic tokens:

```css
.derived-market-block { min-width: 0; margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--hairline); }
.derived-market-block h4 { margin: 0 0 9px; font-size: 13px; }
.derived-market-block .muted { margin: 0; color: var(--muted); }
.peer-selection-tags { display: inline-flex; flex-wrap: wrap; gap: 4px; min-width: 84px; }
.peer-selection-tag { padding: 2px 5px; border: 1px solid var(--hairline-strong); border-radius: 999px; color: var(--body); background: var(--canvas-soft); font-size: 10px; white-space: nowrap; }
.target-row { background: var(--purple-soft); }
.flow-window-table { min-width: 760px; }
```

Update `StaticResourceTest` to load `/js/derived-market-view.js` and assert it contains `同行估值对比`, `资金流窗口汇总`, and no provider credentials.

- [ ] **Step 6: Run focused UI/static tests and commit**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=StaticResourceTest' test
$env:PLAYWRIGHT_CHANNEL='msedge'
npm.cmd run test:ui -- --grep "deterministic peer groups|multi-window order-size flows"
git add src/main/resources/static/js/derived-market-view.js src/main/resources/static/js/views.js src/main/resources/static/styles.css tests/ui/fixtures/partial-snapshot.json tests/ui/dashboard.spec.js src/test/java/com/astock/agent/web/StaticResourceTest.java
git commit -m "feat: show peer valuation and fund flow details"
```

Expected: Java static-resource test and both Playwright tests pass.

Run the Playwright command inside the same port-preflight/start/health/`finally` cleanup wrapper from Step 3 so it exercises freshly copied resources and never reuses an unknown process.

---

### Task 5: Render the Same Details in Overall and Institutional Reports

**Files:**
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/main/resources/static/styles.css`
- Modify: `tests/ui/dashboard.spec.js`

- [ ] **Step 1: Add failing report UI tests**

Extend the existing mocked overall-report response so `report` contains the fixture `industryValuation` and `fundFlowSummary`. Extend the institutional response so `technicalAndFlow.fundFlowSummary` and `valuationAndIndustry.industryValuation` contain the same values.

Add these helpers near `snapshot()` so tests reuse complete `DataSection` objects without mutating the imported fixture:

```javascript
function industryValuationSection() {
  return structuredClone(snapshot().industryValuation);
}

function fundFlowSummarySection() {
  return structuredClone(snapshot().fundFlowSummary);
}
```

Add the exact fields to mocked responses:

```javascript
// overall response.report
industryValuation: industryValuationSection(),
fundFlowSummary: fundFlowSummarySection(),

// institutional response
technicalAndFlow: {
  narrative: "技术与资金",
  fundFlowSummary: fundFlowSummarySection(),
},
valuationAndIndustry: {
  narrative: "估值",
  industryValuation: industryValuationSection(),
},
```

Add assertions after generating each report:

```javascript
await expect(page.locator("#overall-report-output")).toContainText("同行估值对比");
await expect(page.locator("#overall-report-output")).toContainText("五粮液");
await expect(page.locator("#overall-report-output")).toContainText("资金流窗口汇总");
await expect(page.locator("#overall-report-output")).toContainText("近 20 日");

await expect(page.locator("#agent-output")).toContainText("同行估值对比");
await expect(page.locator("#agent-output")).toContainText("资金流窗口汇总");
```

Add this unavailable-data test to confirm existing narratives remain visible and the UI does not fabricate empty tables:

```javascript
test("reports keep narratives when deterministic details are absent", async ({ page }) => {
  await page.route("**/api/agent/overall-report", (route) => route.fulfill({ json: {
    status: "MODEL_ASSISTED",
    report: {
      overallConclusion: "总体叙述仍然可见",
      technicalAndCapital: "资金叙述仍然可见",
      valuationAndIndustry: "估值叙述仍然可见",
      industryValuation: null,
      fundFlowSummary: null,
      bullishEvidence: [], bearishEvidence: [], riskFactors: [], scenarios: {},
      conflictsAndMissingData: [], sourceReferences: [],
      disclaimer: "仅供学习研究，不构成投资建议",
    },
  } }));
  await page.route("**/api/agent/analyze", (route) => route.fulfill({ json: {
    executiveSummary: "研究叙述仍然可见",
    technicalAndFlow: { narrative: "技术叙述", fundFlowSummary: null },
    fundamentals: { narrative: "基本面叙述" },
    valuationAndIndustry: { narrative: "行业叙述", industryValuation: null },
    coreDrivers: [], catalysts: [], risks: [], conflicts: [], missingData: [],
    invalidationConditions: [], sources: [],
    disclaimer: "仅供学习研究，不构成投资建议",
  } }));

  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();
  await page.getByRole("button", { name: /生成总体报告/ }).click();
  await page.getByRole("button", { name: "生成研究报告" }).click();

  await expect(page.locator("#overall-report-output")).toContainText("总体叙述仍然可见");
  await expect(page.locator("#agent-output")).toContainText("研究叙述仍然可见");
  await expect(page.getByRole("table", { name: "同行估值对比" })).toHaveCount(0);
  await expect(page.getByRole("table", { name: "资金流窗口汇总" })).toHaveCount(0);
});
```

- [ ] **Step 2: Run report UI tests and verify RED**

```powershell
$env:PLAYWRIGHT_CHANNEL='msedge'
npm.cmd run test:ui -- --grep "overall report is independent|structured report"
```

Expected: assertions for the two new tables fail.

Run this Playwright command inside the Task 4 Step 3 server lifecycle wrapper, substituting only the inner `npm.cmd` command.

- [ ] **Step 3: Integrate the shared renderer into report views**

Import `renderPeerValuationTable` and `renderFundFlowSummary` into `app.js`.

In `renderInstitutionalReport`, render the fund summary immediately after `技术与资金` and the peer table immediately after `估值与行业`. Pass an empty target object because report-level `IndustryValuationData` already contains target PE/PB and selected peers; build the target row from report data with label `目标股票` when quote metadata is not present.

```javascript
${renderModuleAnalysis("技术与资金", report.technicalAndFlow, technical)}
${renderFundFlowSummary(report.technicalAndFlow?.fundFlowSummary)}
${renderModuleAnalysis("基本面与机构预期", report.fundamentals, fundamental)}
${renderModuleAnalysis("估值与行业", report.valuationAndIndustry, valuation)}
${renderPeerValuationTable(report.valuationAndIndustry?.industryValuation)}
```

In `renderOverallReportResponse`, render `report.fundFlowSummary` after the technical/capital narrative and `report.industryValuation` after the valuation/industry narrative. Shared renderers must return an empty string for null data, preserving all existing narrative, diagnostic and disclaimer markup.

```javascript
${renderOverallSection("技术与资金", report.technicalAndCapital)}
${renderFundFlowSummary(report.fundFlowSummary)}
${renderOverallSection("估值与行业", report.valuationAndIndustry)}
${renderPeerValuationTable(report.industryValuation)}
```

Ensure the structured details remain part of `state.overallReportResponse` and `state.institutionalReport`, so the existing tab-remount and failed-replacement protections preserve them automatically.

- [ ] **Step 4: Run report and full UI tests and commit**

```powershell
$env:PLAYWRIGHT_CHANNEL='msedge'
npm.cmd run test:ui -- --grep "overall report is independent|structured report"
npm.cmd run test:ui
git add src/main/resources/static/js/app.js src/main/resources/static/styles.css tests/ui/dashboard.spec.js
git commit -m "feat: render deterministic details in reports"
```

Expected: focused report tests and the full Playwright suite pass.

Run both Playwright commands inside separate Task 4 Step 3 lifecycle wrappers so each run starts the current code and cleans up its owned listener.

---

### Task 6: Document, Audit, and Verify the Complete Feature

**Files:**
- Modify: `README.md`
- Verify: all files from Tasks 1–5

- [ ] **Step 1: Update user-facing documentation**

Add a README section explaining:

- valuation comparison uses five nearest-market-cap peers plus three industry leaders;
- duplicates merge both tags;
- fund flow shows latest day and 5/20-day main, super-large, large, medium and small net flows;
- fewer samples are reported honestly through `sampleDays`;
- Eastmoney remains the peer source and Eastmoney/Sina remain the fund-flow chain;
- Northbound, industry-wide flow and seat-level detail are not included.

- [ ] **Step 2: Run focused Java verification**

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=IndustryValuationCalculatorTest,IndustryValuationServiceTest,FundFlowSummaryCalculatorTest,ResearchAggregationServiceTest,ModuleAnalysisFactoryTest,InstitutionalReportComposerTest,OverallResearchReportTest,OverallReportServiceTest,AgentControllerTest,StaticResourceTest' test
```

Expected: all focused tests pass.

- [ ] **Step 3: Run the default offline suite and package**

The ignored real local configuration still makes `LocalModelConfigurationExampleTest` environment-dependent. Per the existing user decision, exclude only that test:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=!LocalModelConfigurationExampleTest' clean test
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-DskipTests' package
```

Expected: offline tests and packaging succeed on Java 21.

- [ ] **Step 4: Run UI verification and inspect screenshots**

```powershell
$env:PLAYWRIGHT_CHANNEL='msedge'
npm.cmd run test:ui
```

Run the full suite inside the Task 4 Step 3 server lifecycle wrapper and substitute this command for the focused command.

Inspect:

- `target/ui-screenshots/agent-1440x1000.png`
- `target/ui-screenshots/agent-1024x768.png`
- `target/ui-screenshots/agent-768x1024.png`
- `target/ui-screenshots/agent-390x844.png`
- the matching four dashboard screenshots.

Check table horizontal scrolling is local, the page has no unintended horizontal overflow, labels fit, and fixed headers do not cover content.

- [ ] **Step 5: Run live-data verification without hiding external failures**

```powershell
.\scripts\verify-data.cmd
```

Expected: success when external quote/K-line sources are reachable. If it repeats the known `No core quote or K-line source is available` errors, record that as an external-source limitation rather than changing this feature or fabricating fixtures.

- [ ] **Step 6: Audit whitespace, secrets, provider copy and worktree scope**

```powershell
git diff --check
rg -n -i "api[-_]?key|authorization|bearer|token|secret" src/main src/test README.md docs/superpowers/plans/2026-08-04-peer-valuation-fund-flow-report.md
rg -n "Northbound|北向资金|行业资金流|席位明细" src/main/resources/static src/main/java
git status --short
```

Expected: no whitespace errors; secret matches are placeholders, property names or redaction tests only; unsupported data is not presented as available; `out/` remains untracked and is not staged.

- [ ] **Step 7: Review acceptance criteria and commit documentation**

Confirm all eight criteria in `docs/superpowers/specs/2026-08-04-peer-valuation-fund-flow-report-design.md` with direct code or test evidence, then:

```powershell
git add README.md
git commit -m "docs: explain peer and fund flow report details"
```

Expected: current branch contains only scoped commits and `git status --short` reports only the pre-existing untracked `out/` directory.
