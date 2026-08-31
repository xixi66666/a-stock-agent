# 财报分析(Financial Report Analysis)实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 主页新增「财报分析」Tab:基于 Sina 财报三表多期历史,Java 确定性计算 F-Score 质量评分与多期趋势,DeepSeek 生成受限叙事(校验 + 确定性回退)。

**Architecture:** 数据层改造 `SinaFinanceClient` 保留 12 期历史并按报告期对齐(累计口径 + 标准 TTM 组法);新增纯函数评分/趋势引擎(`analysis/financial/`);镜像 `agent/overall/` 模式新增 `agent/financial/` 叙事管线;`POST /api/agent/financial-report` + 第 8 个 Tab。

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI ChatClient(DeepSeek OpenAI 兼容端点), Jackson, Caffeine, ECharts, Lucide, Playwright。

**设计文档:** `docs/superpowers/specs/2026-08-31-financial-report-analysis-design.md`

## Global Constraints

- 所有测试默认离线;实盘/外部 Provider 测试必须标 `@Tag("external")`(pom 默认排除,`-Pexternal` 才跑)。
- 模型只写叙事,不算数;数字必须出自证据包,校验失败 → 修复一次 → `DETERMINISTIC_FALLBACK`。
- 缺失数据必须 `UNVERIFIED`/`UNAVAILABLE` + 原因,禁止补造数值;Provenance 全程保留。
- 真实 API Key 只能进 `config/application-local.yml`(Git 忽略);example 只放占位符;日志不得出现 `key|token|secret|authorization` 查询参数。
- A 股语义红涨绿跌;颜色不能是唯一信息载体;设计令牌见 AGENTS.md。
- Maven 命令统一:`$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path; $env:Path = "$env:JAVA_HOME\bin;$env:Path"; .\mvnw.cmd '-Dmaven.repo.local=.m2/repository' <goal>`;验证输出必须是 Java 21。
- 接口字段名沿用既有命名:`securityCode`、`reportPeriodRange`、`generationMode` 等 camelCase JSON。
- 提交需经用户确认后才执行(仓库约定 dirty worktree 保留用户改动)。

---

### Task 1: 多期财报域记录 + Sina 多期解析 + Gateway 方法

**Files:**
- Create: `src/main/java/com/astock/agent/marketdata/model/FinancialStatementHistory.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/FinancialPeriodStatement.java`
- Modify: `src/main/java/com/astock/agent/marketdata/provider/sina/SinaFinanceClient.java:90-120`(新增解析与对齐,不动 `fetchStatements`)
- Modify: `src/main/java/com/astock/agent/analysis/ResearchGateway.java`(新增方法)
- Modify: `src/main/java/com/astock/agent/analysis/ProviderResearchGateway.java`(实现新方法)
- Modify: `src/test/java/com/astock/agent/analysis/ResearchAggregationServiceTest.java`(StubGateway 补方法)
- Create: `src/test/resources/fixtures/sina/statements-history-lrb-600519.json`
- Create: `src/test/resources/fixtures/sina/statements-history-fzb-600519.json`
- Create: `src/test/resources/fixtures/sina/statements-history-llb-600519.json`
- Create: `src/test/java/com/astock/agent/marketdata/provider/SinaStatementHistoryTest.java`

**Interfaces:**
- Produces:
  - `record FinancialStatementHistory(SecurityId security, List<FinancialPeriodStatement> periods)`(periods 按报告期升序;`periodCount()`)
  - `record FinancialPeriodStatement(LocalDate reportPeriod, BigDecimal operatingRevenue, BigDecimal operatingCost, BigDecimal netProfit, BigDecimal netProfitAttributable, BigDecimal operatingCashFlow, BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal currentAssets, BigDecimal currentLiabilities, BigDecimal shareCapital, BigDecimal equityAttributable)`(除 reportPeriod 外均可 null)
  - `SinaFinanceClient.parseStatementHistory(String body)` → `Map<LocalDate, Map<String, BigDecimal>>`(public,供 fixture 测试)
  - `SinaFinanceClient.fetchStatementHistory(SecurityId)` → `DataSection<FinancialStatementHistory>`
  - `ResearchGateway.financialHistory(SecurityId)` → `DataSection<FinancialStatementHistory>`

- [ ] **Step 1: 写失败测试(记录 + 解析 + 对齐 + Gateway 桩)**

先建三个 sanitized 多期 fixture(仅公开财报数据,含 12 期;lrb 含 `营业总收入/营业成本/净利润/归属于母公司所有者的净利润`,fzb 含 `资产总计/负债合计/流动资产合计/流动负债合计/实收资本(或股本)/归属于母公司股东权益合计`,llb 含 `经营活动产生的现金流量净额`)。JSON 形状与现有 fixture 一致:

```json
{"result":{"data":{"report_list":{"20230630":{"data":[{"item_title":"营业总收入","item_value":"69380000000","item_tongbi":"18.7"}]},"20230930":{"data":[...]}}}}}
```

数值用 20230630 → 20260630 连续 12 期合成数(茅台量级、含负增长期,便于趋势测试)。测试类:

```java
package com.astock.agent.marketdata.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.Exchange;
import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.sina.SinaFinanceClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SinaStatementHistoryTest {

    private final SinaFinanceClient sina = new SinaFinanceClient();

    @Test
    void parsesTwelvePeriodsPerStatement() throws Exception {
        Map<LocalDate, Map<String, java.math.BigDecimal>> lrb =
                sina.parseStatementHistory(fixture("sina/statements-history-lrb-600519.json"));

        assertThat(lrb).hasSize(12);
        assertThat(lrb.get(LocalDate.parse("2026-06-30"))).containsKey("营业总收入");
    }

    @Test
    void alignsThreeStatementsIntoAscendingHistory() {
        SecurityId security = new SecurityId("600519", Exchange.SHANGHAI);
        FinancialStatementHistory history = sina.alignStatementHistory(security,
                parse("sina/statements-history-lrb-600519.json"),
                parse("sina/statements-history-fzb-600519.json"),
                parse("sina/statements-history-llb-600519.json"));

        assertThat(history.periodCount()).isEqualTo(12);
        List<FinancialPeriodStatement> periods = history.periods();
        assertThat(periods.get(0).reportPeriod()).isEqualTo(LocalDate.parse("2023-06-30"));
        assertThat(periods.get(11).reportPeriod()).isEqualTo(LocalDate.parse("2026-06-30"));
        FinancialPeriodStatement latest = periods.get(11);
        assertThat(latest.operatingRevenue()).isNotNull();
        assertThat(latest.totalAssets()).isNotNull();
        assertThat(latest.operatingCashFlow()).isNotNull();
        assertThat(latest.equityAttributable()).isNotNull();
    }

    @Test
    void toleratesMissingTableAndPeriod() {
        SecurityId security = new SecurityId("600519", Exchange.SHANGHAI);
        FinancialStatementHistory history = sina.alignStatementHistory(security,
                parse("sina/statements-history-lrb-600519.json"),
                parse("sina/statements-history-fzb-600519.json"),
                Map.of());

        assertThat(history.periodCount()).isEqualTo(12);
        assertThat(history.periods().get(0).operatingCashFlow()).isNull();
    }

    private Map<LocalDate, Map<String, java.math.BigDecimal>> parse(String name) {
        try {
            return sina.parseStatementHistory(fixture(name));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String fixture(String name) throws Exception {
        try (var stream = SinaStatementHistoryTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=SinaStatementHistoryTest' test
```

预期:编译失败(方法不存在)。

- [ ] **Step 3: 实现域记录**

`FinancialStatementHistory.java`:

```java
package com.astock.agent.marketdata.model;

import java.util.List;
import java.util.Objects;

/** 按报告期升序对齐的三张财务报表历史。 */
public record FinancialStatementHistory(
        SecurityId security,
        List<FinancialPeriodStatement> periods) {

    public FinancialStatementHistory {
        security = Objects.requireNonNull(security, "security is required");
        periods = periods == null ? List.of() : List.copyOf(periods);
    }

    public int periodCount() {
        return periods.size();
    }
}
```

`FinancialPeriodStatement.java`:

```java
package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单个报告期的规范化财报字段。
 *
 * <p>lrb/llb 字段为年初至今累计口径;fzb 字段为时点值。缺失字段为 null,
 * 下游必须显式处理,不得补造数值。</p>
 */
public record FinancialPeriodStatement(
        LocalDate reportPeriod,
        BigDecimal operatingRevenue,
        BigDecimal operatingCost,
        BigDecimal netProfit,
        BigDecimal netProfitAttributable,
        BigDecimal operatingCashFlow,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal currentAssets,
        BigDecimal currentLiabilities,
        BigDecimal shareCapital,
        BigDecimal equityAttributable) {
}
```

- [ ] **Step 4: 实现解析与对齐**

`SinaFinanceClient` 新增(文件已有 import;追加方法):

```java
    public Map<LocalDate, Map<String, BigDecimal>> parseStatementHistory(String body) {
        try {
            JsonNode reports = MAPPER.readTree(body).path("result").path("data").path("report_list");
            Map<LocalDate, Map<String, BigDecimal>> result = new LinkedHashMap<>();
            var fields = reports.fieldNames();
            while (fields.hasNext()) {
                String key = fields.next();
                if (key == null || key.length() != 8) {
                    continue;
                }
                LocalDate period = LocalDate.of(
                        Integer.parseInt(key.substring(0, 4)),
                        Integer.parseInt(key.substring(4, 6)),
                        Integer.parseInt(key.substring(6, 8)));
                Map<String, BigDecimal> items = new LinkedHashMap<>();
                for (JsonNode item : reports.path(key).path("data")) {
                    String title = item.path("item_title").asText();
                    BigDecimal value = decimalOrNull(item.path("item_value"));
                    if (title.isBlank() || value == null) {
                        continue;
                    }
                    items.put(title, value);
                }
                result.put(period, items);
            }
            if (result.isEmpty()) {
                throw new IllegalArgumentException("Sina response contains no report period");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse Sina statement history", exception);
        }
    }

    public FinancialStatementHistory alignStatementHistory(SecurityId security,
            Map<LocalDate, Map<String, BigDecimal>> lrb,
            Map<LocalDate, Map<String, BigDecimal>> fzb,
            Map<LocalDate, Map<String, BigDecimal>> llb) {
        TreeSet<LocalDate> all = new TreeSet<>();
        all.addAll(lrb.keySet());
        all.addAll(fzb.keySet());
        all.addAll(llb.keySet());
        List<FinancialPeriodStatement> periods = new ArrayList<>();
        for (LocalDate period : all) {
            periods.add(new FinancialPeriodStatement(
                    period,
                    value(lrb, period, "营业总收入", "营业收入"),
                    value(lrb, period, "营业成本"),
                    value(lrb, period, "净利润"),
                    value(lrb, period, "归属于母公司所有者的净利润", "归属于母公司股东的净利润"),
                    value(llb, period, "经营活动产生的现金流量净额"),
                    value(fzb, period, "资产总计"),
                    value(fzb, period, "负债合计"),
                    value(fzb, period, "流动资产合计"),
                    value(fzb, period, "流动负债合计"),
                    value(fzb, period, "实收资本(或股本)"),
                    value(fzb, period, "归属于母公司股东权益合计")));
        }
        return new FinancialStatementHistory(security, periods);
    }

    public DataSection<FinancialStatementHistory> fetchStatementHistory(SecurityId security) {
        ensureLiveClient();
        String paperCode = (security.exchange() == com.astock.agent.marketdata.model.Exchange.SHANGHAI ? "sh" : "sz")
                + security.code();
        Map<LocalDate, Map<String, BigDecimal>> lrb = Map.of();
        Map<LocalDate, Map<String, BigDecimal>> fzb = Map.of();
        Map<LocalDate, Map<String, BigDecimal>> llb = Map.of();
        URI firstUri = null;
        try {
            for (String type : List.of("lrb", "fzb", "llb")) {
                URI uri = URI.create("https://quotes.sina.cn/cn/api/openapi.php/"
                        + "CompanyFinanceService.getFinanceReport2022?paperCode=" + paperCode
                        + "&source=" + type + "&type=0&page=1&num=12");
                if (firstUri == null) {
                    firstUri = uri;
                }
                Map<LocalDate, Map<String, BigDecimal>> parsed = parseStatementHistory(
                        http.get(ProviderId.SINA, uri, "https://finance.sina.com.cn/").utf8Text());
                if ("lrb".equals(type)) { lrb = parsed; }
                else if ("fzb".equals(type)) { fzb = parsed; }
                else { llb = parsed; }
            }
            FinancialStatementHistory history = alignStatementHistory(security, lrb, fzb, llb);
            if (history.periodCount() == 0) {
                return DataSection.unavailable("Sina statements returned no aligned periods");
            }
            return DataSection.healthy(history,
                    new Provenance(ProviderId.SINA.displayName(), firstUri, null, clock.instant(), false, null));
        } catch (Exception exception) {
            return DataSection.unavailable("Sina statements failed: " + exception.getMessage());
        }
    }

    private static BigDecimal value(Map<LocalDate, Map<String, BigDecimal>> table,
            LocalDate period, String... titles) {
        Map<String, BigDecimal> items = table.get(period);
        if (items == null) {
            return null;
        }
        for (String title : titles) {
            if (items.containsKey(title)) {
                return items.get(title);
            }
        }
        return null;
    }
```

需要的 import 追加:`java.util.TreeSet`、`com.astock.agent.marketdata.model.FinancialPeriodStatement`、`com.astock.agent.marketdata.model.FinancialStatementHistory`。注意 `fetchStatements` 的 `num=8` 保持不变(基本面 Tab 契约不动)。

- [ ] **Step 5: Gateway 方法**

`ResearchGateway.java` 追加:

```java
    DataSection<FinancialStatementHistory> financialHistory(SecurityId security);
```

(import `com.astock.agent.marketdata.model.FinancialStatementHistory`)

`ProviderResearchGateway.java` 追加:

```java
    @Override public DataSection<FinancialStatementHistory> financialHistory(SecurityId security) {
        return sina.fetchStatementHistory(security);
    }
```

`ResearchAggregationServiceTest.java` 的 `StubGateway` 追加:

```java
        @Override
        public DataSection<FinancialStatementHistory> financialHistory(SecurityId security) {
            return DataSection.unavailable("not stubbed");
        }
```

(加 import)

- [ ] **Step 6: 运行测试通过**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=SinaStatementHistoryTest,ExtendedProviderContractTest,ResearchAggregationServiceTest' test
```

预期:PASS(含既有测试不回归)。

- [ ] **Step 7: 提交(经用户确认后)**

```bash
git add src/main/java/com/astock/agent/marketdata/model/FinancialStatementHistory.java src/main/java/com/astock/agent/marketdata/model/FinancialPeriodStatement.java src/main/java/com/astock/agent/marketdata/provider/sina/SinaFinanceClient.java src/main/java/com/astock/agent/analysis/ResearchGateway.java src/main/java/com/astock/agent/analysis/ProviderResearchGateway.java src/test/java/com/astock/agent/marketdata/provider/SinaStatementHistoryTest.java src/test/resources/fixtures/sina/statements-history-*.json src/test/java/com/astock/agent/analysis/ResearchAggregationServiceTest.java
git commit -m "feat: parse multi-period financial statement history from Sina"
```

---

### Task 2: TTM 工具 + 财务质量评分引擎(F-Score)

**Files:**
- Create: `src/main/java/com/astock/agent/analysis/financial/FinancialTtm.java`
- Create: `src/main/java/com/astock/agent/analysis/financial/FinancialQualityScore.java`
- Create: `src/main/java/com/astock/agent/analysis/financial/FinancialQualityScorer.java`
- Create: `src/test/java/com/astock/agent/analysis/financial/FinancialQualityScorerTest.java`

**Interfaces:**
- Consumes: `FinancialStatementHistory`、`FinancialPeriodStatement`(Task 1)
- Produces(后续 Task 依赖):
  - `FinancialTtm.ttm(List<FinancialPeriodStatement>, int index, Function<FinancialPeriodStatement, BigDecimal>)` → BigDecimal(累计口径组法;组不出返回 null)
  - `FinancialTtm.periodAt(List<FinancialPeriodStatement>, LocalDate)` → FinancialPeriodStatement 或 null
  - `FinancialTtm.percent(BigDecimal numerator, BigDecimal denominator)` → BigDecimal 或 null
  - `record FinancialQualityScore(int total, String tier, int evaluatedSignals, List<SignalResult> signals, boolean sufficientData)`
  - `record SignalResult(int number, String name, SignalStatus status, String evidence)`;`enum SignalStatus { PASS, FAIL, UNVERIFIED }`
  - `FinancialQualityScorer.score(FinancialStatementHistory history, boolean financialIndustry)` → FinancialQualityScore
  - 常量 `FinancialQualityScorer.RULE_VERSION = "financial-fscore-v1"`

- [ ] **Step 1: 写失败测试**

```java
package com.astock.agent.analysis.financial;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.Exchange;
import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class FinancialQualityScorerTest {

    private final FinancialQualityScorer scorer = new FinancialQualityScorer();

    private static final String[] PERIODS = {
        "2023-06-30", "2023-09-30", "2023-12-31", "2024-03-31",
        "2024-06-30", "2024-09-30", "2024-12-31", "2025-03-31",
        "2025-06-30", "2025-09-30", "2025-12-31", "2026-03-31",
    };

    @Test
    void healthyGrowthHistoryScoresHigh() {
        FinancialStatementHistory history = history(period -> {
            int index = java.util.Arrays.asList(PERIODS).indexOf(period.toString());
            return statement(period, 1000 + index * 50, 350 + index * 10, 120 + index * 8,
                    110 + index * 8, 300 + index * 20, 5000 + index * 200, 2000 + index * 60,
                    1500 + index * 40, 800 + index * 30, 500, 2500 + index * 100);
        });
        FinancialQualityScore score = scorer.score(history, false);

        assertThat(score.sufficientData()).isTrue();
        assertThat(score.total()).isGreaterThanOrEqualTo(7);
        assertThat(score.tier()).isIn("良", "优");
        assertThat(score.signals()).hasSize(9);
        assertThat(score.signals()).noneMatch(signal -> signal.status() == SignalStatus.UNVERIFIED);
    }

    @Test
    void deterioratingHistoryScoresLow() {
        FinancialStatementHistory history = history(period -> {
            int index = java.util.Arrays.asList(PERIODS).indexOf(period.toString());
            return statement(period, 1000 - index * 20, 350 + index * 5, Math.max(10, 120 - index * 10),
                    90 - index * 8, Math.max(20, 300 - index * 30), 5000 + index * 300, 2000 + index * 150,
                    1500 - index * 50, 800 + index * 60, 500 + index * 10, 2500 - index * 50);
        });
        FinancialQualityScore score = scorer.score(history, false);

        assertThat(score.sufficientData()).isTrue();
        assertThat(score.total()).isLessThanOrEqualTo(2);
        assertThat(score.tier()).isEqualTo("弱");
    }

    @Test
    void missingFieldsBecomeUnverifiedAndExcludedFromTotal() {
        FinancialStatementHistory history = history(period -> statement(period, null, null, null,
                null, null, 5000, 2000, null, null, null, null));
        FinancialQualityScore score = scorer.score(history, false);

        assertThat(score.evaluatedSignals()).isLessThan(9);
        assertThat(score.signals().stream().filter(s -> s.status() == SignalStatus.UNVERIFIED)).isNotEmpty();
    }

    @Test
    void fewerThanFourPeriodsIsInsufficient() {
        FinancialStatementHistory history = history(period -> statement(period, 1000, 350, 120, 110, 300, 5000, 2000, 1500, 800, 500, 2500));
        FinancialQualityScore score = scorer.score(history.subset(3), false);

        assertThat(score.sufficientData()).isFalse();
        assertThat(score.tier()).isEqualTo("数据不足");
    }

    @Test
    void financialIndustryMarksMarginAndTurnoverUnverified() {
        FinancialStatementHistory history = history(period -> {
            int index = java.util.Arrays.asList(PERIODS).indexOf(period.toString());
            return statement(period, 1000 + index * 50, 350 + index * 10, 120 + index * 8,
                    110 + index * 8, 300 + index * 20, 5000 + index * 200, 2000 + index * 60,
                    1500 + index * 40, 800 + index * 30, 500, 2500 + index * 100);
        });
        FinancialQualityScore score = scorer.score(history, true);

        assertThat(score.signals().get(7).status()).isEqualTo(SignalStatus.UNVERIFIED);
        assertThat(score.signals().get(8).status()).isEqualTo(SignalStatus.UNVERIFIED);
    }

    @Test
    void ttmUsesCumulativeComposition() {
        FinancialPeriodStatement q3 = statement("2026-03-31", 300, 105, 36, 33, 90, 6200, 2300, 1800, 950, 500, 3100);
        FinancialPeriodStatement annual = statement("2025-12-31", 1300, 455, 156, 143, 390, 6000, 2200, 1750, 900, 500, 3000);
        FinancialPeriodStatement sameLastYear = statement("2025-03-31", 270, 95, 32, 29, 80, 5800, 2100, 1700, 880, 500, 2900);
        List<FinancialPeriodStatement> periods = new ArrayList<>();
        periods.add(sameLastYear);
        periods.add(annual);
        periods.add(q3);

        BigDecimal ttmRevenue = FinancialTtm.ttm(periods, 2, FinancialPeriodStatement::operatingRevenue);

        // TTM = 当期累计 + 上年年报 - 上年同期 = 300 + 1300 - 270
        assertThat(ttmRevenue).isEqualByComparingTo("1330");
    }

    private static FinancialPeriodStatement statement(String period, Number revenue, Number cost,
            Number netProfit, Number attributable, Number cashFlow, Number assets,
            Number liabilities, Number currentAssets, Number currentLiabilities,
            Number shareCapital, Number equity) {
        return new FinancialPeriodStatement(LocalDate.parse(period), dec(revenue), dec(cost),
                dec(netProfit), dec(attributable), dec(cashFlow), dec(assets), dec(liabilities),
                dec(currentAssets), dec(currentLiabilities), dec(shareCapital), dec(equity));
    }

    private static BigDecimal dec(Number value) {
        return value == null ? null : BigDecimal.valueOf(value.doubleValue());
    }

    private static FinancialStatementHistory history(UnaryOperator<FinancialPeriodStatement> builder) {
        List<FinancialPeriodStatement> periods = new ArrayList<>();
        for (String period : PERIODS) {
            periods.add(builder.apply(statement(period, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
        }
        return new FinancialStatementHistory(new SecurityId("600519", Exchange.SHANGHAI), periods);
    }
}
```

- [ ] **Step 2: 运行确认失败** — `'-Dtest=FinancialQualityScorerTest' test`,预期编译失败。

- [ ] **Step 3: 实现 FinancialTtm**

```java
package com.astock.agent.analysis.financial;

import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * 累计口径的 TTM 组法与通用财务计算辅助。
 *
 * <p>lrb/llb 报告期数值是年初至今累计:TTM = 当期累计 + 上年年报 − 上年同期;
 * 当期是年报时 TTM 即全年累计。组不出(期数不足或字段缺失)返回 null。</p>
 */
public final class FinancialTtm {

    private FinancialTtm() {
    }

    public static BigDecimal ttm(List<FinancialPeriodStatement> periods, int index,
            Function<FinancialPeriodStatement, BigDecimal> value) {
        FinancialPeriodStatement current = periods.get(index);
        BigDecimal cumulative = value.apply(current);
        if (cumulative == null) {
            return null;
        }
        if (current.reportPeriod().getMonthValue() == 12) {
            return cumulative;
        }
        FinancialPeriodStatement sameLastYear = periodAt(periods,
                current.reportPeriod().minusYears(1));
        FinancialPeriodStatement priorAnnual = annualOfPreviousYear(periods, current.reportPeriod());
        if (sameLastYear == null || priorAnnual == null
                || value.apply(sameLastYear) == null || value.apply(priorAnnual) == null) {
            return null;
        }
        return cumulative.add(value.apply(priorAnnual)).subtract(value.apply(sameLastYear));
    }

    public static FinancialPeriodStatement periodAt(List<FinancialPeriodStatement> periods,
            LocalDate period) {
        for (FinancialPeriodStatement statement : periods) {
            if (statement.reportPeriod().equals(period)) {
                return statement;
            }
        }
        return null;
    }

    public static FinancialPeriodStatement annualOfPreviousYear(
            List<FinancialPeriodStatement> periods, LocalDate current) {
        FinancialPeriodStatement found = null;
        for (FinancialPeriodStatement statement : periods) {
            LocalDate period = statement.reportPeriod();
            if (period.getYear() == current.getYear() - 1 && period.getMonthValue() == 12) {
                found = statement;
            }
        }
        return found;
    }

    public static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 4, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: 实现评分记录与引擎**

`FinancialQualityScore.java`:

```java
package com.astock.agent.analysis.financial;

import java.util.List;

/** F-Score 评分结果:0-9 分、档位与逐信号明细。 */
public record FinancialQualityScore(
        int total,
        String tier,
        int evaluatedSignals,
        List<SignalResult> signals,
        boolean sufficientData) {

    public FinancialQualityScore {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    public enum SignalStatus { PASS, FAIL, UNVERIFIED }

    public record SignalResult(int number, String name, SignalStatus status, String evidence) {
    }

    public static FinancialQualityScore insufficient() {
        return new FinancialQualityScore(0, "数据不足", 0, List.of(), false);
    }
}
```

`FinancialQualityScorer.java`(信号 1-9 见 spec §5;evidence 用带单位的字符串):

```java
package com.astock.agent.analysis.financial;

import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Piotroski F-Score 的 A 股 TTM 适配版:9 个信号各 1 分。
 *
 * <p>流量类信号用 TTM 组法,时点类信号取最新期与上年同期比较;
 * 输入缺失的信号标 UNVERIFIED,不计入总分与分母;期数少于 4 整体数据不足。</p>
 */
public final class FinancialQualityScorer {

    public static final String RULE_VERSION = "financial-fscore-v1";

    public FinancialQualityScore score(FinancialStatementHistory history, boolean financialIndustry) {
        List<FinancialPeriodStatement> periods = history.periods();
        if (periods.size() < 4) {
            return FinancialQualityScore.insufficient();
        }
        int last = periods.size() - 1;
        List<FinancialQualityScore.SignalResult> signals = new ArrayList<>();
        signals.add(signal(1, "TTM ROA 为正", ttmRoaPositive(periods, last)));
        signals.add(signal(2, "TTM 经营现金流为正", ttmCashFlowPositive(periods, last)));
        signals.add(signal(3, "ROA 改善", deltaTtmRoa(periods, last)));
        signals.add(signal(4, "现金流质量(CFO > 净利润)", cashQuality(periods, last)));
        signals.add(signal(5, "杠杆改善(资产负债率下降)", pointInTimeImproving(periods, last,
                FinancialQualityScorer::debtRatio, "资产负债率")));
        signals.add(signal(6, "流动性改善(流动比率上升)", pointInTimeImproving(periods, last,
                FinancialQualityScorer::currentRatio, "流动比率")));
        signals.add(signal(7, "无股本稀释", noDilution(periods, last)));
        if (financialIndustry) {
            signals.add(new FinancialQualityScore.SignalResult(8, "毛利率改善",
                    FinancialQualityScore.SignalStatus.UNVERIFIED, "金融行业财报无传统毛利率口径"));
            signals.add(new FinancialQualityScore.SignalResult(9, "资产周转率改善",
                    FinancialQualityScore.SignalStatus.UNVERIFIED, "金融行业财报无传统周转率口径"));
        } else {
            signals.add(signal(8, "毛利率改善", deltaTtmGrossMargin(periods, last)));
            signals.add(signal(9, "资产周转率改善", deltaTtmAssetTurnover(periods, last)));
        }
        int total = (int) signals.stream()
                .filter(item -> item.status() == FinancialQualityScore.SignalStatus.PASS).count();
        int evaluated = (int) signals.stream()
                .filter(item -> item.status() != FinancialQualityScore.SignalStatus.UNVERIFIED).count();
        return new FinancialQualityScore(total, tier(total), evaluated, signals, true);
    }

    private static FinancialQualityScore.SignalResult signal(int number, String name,
            FinancialQualityScore.SignalStatus status, String evidence) {
        return new FinancialQualityScore.SignalResult(number, name, status, evidence);
    }

    private static FinancialQualityScore.SignalResult signal(int number, String name, Boolean pass) {
        return pass == null
                ? signal(number, name, FinancialQualityScore.SignalStatus.UNVERIFIED, "所需字段或历史期数不足")
                : signal(number, name,
                        pass ? FinancialQualityScore.SignalStatus.PASS : FinancialQualityScore.SignalStatus.FAIL,
                        pass ? "条件成立" : "条件不成立");
    }

    private static Boolean ttmRoaPositive(List<FinancialPeriodStatement> periods, int index) {
        BigDecimal np = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::netProfitAttributable);
        BigDecimal assets = averageAssets(periods, index);
        BigDecimal roa = FinancialTtm.percent(np, assets);
        return roa == null ? null : roa.signum() > 0;
    }

    private static Boolean ttmCashFlowPositive(List<FinancialPeriodStatement> periods, int index) {
        BigDecimal cashFlow = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::operatingCashFlow);
        return cashFlow == null ? null : cashFlow.signum() > 0;
    }

    private static Boolean deltaTtmRoa(List<FinancialPeriodStatement> periods, int index) {
        if (index - 4 < 0) {
            return null;
        }
        BigDecimal current = ttmRoa(periods, index);
        BigDecimal prior = ttmRoa(periods, index - 4);
        if (current == null || prior == null) {
            return null;
        }
        return current.compareTo(prior) > 0;
    }

    private static BigDecimal ttmRoa(List<FinancialPeriodStatement> periods, int index) {
        BigDecimal np = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::netProfitAttributable);
        return FinancialTtm.ratio(np, averageAssets(periods, index));
    }

    private static Boolean cashQuality(List<FinancialPeriodStatement> periods, int index) {
        BigDecimal cashFlow = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::operatingCashFlow);
        BigDecimal netProfit = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::netProfit);
        if (cashFlow == null || netProfit == null) {
            return null;
        }
        return cashFlow.compareTo(netProfit) > 0;
    }

    private static Boolean pointInTimeImproving(List<FinancialPeriodStatement> periods, int index,
            java.util.function.Function<FinancialPeriodStatement, BigDecimal> ratio, String name) {
        if (index - 4 < 0) {
            return null;
        }
        BigDecimal current = ratio.apply(periods.get(index));
        BigDecimal prior = ratio.apply(periods.get(index - 4));
        if (current == null || prior == null) {
            return null;
        }
        return current.compareTo(prior) < 0;
    }

    private static Boolean noDilution(List<FinancialPeriodStatement> periods, int index) {
        if (index - 4 < 0) {
            return null;
        }
        BigDecimal current = periods.get(index).shareCapital();
        BigDecimal prior = periods.get(index - 4).shareCapital();
        if (current == null || prior == null) {
            return null;
        }
        return current.compareTo(prior) <= 0;
    }

    private static Boolean deltaTtmGrossMargin(List<FinancialPeriodStatement> periods, int index) {
        if (index - 4 < 0) {
            return null;
        }
        BigDecimal current = ttmGrossMargin(periods, index);
        BigDecimal prior = ttmGrossMargin(periods, index - 4);
        if (current == null || prior == null) {
            return null;
        }
        return current.compareTo(prior) > 0;
    }

    private static Boolean deltaTtmAssetTurnover(List<FinancialPeriodStatement> periods, int index) {
        if (index - 4 < 0) {
            return null;
        }
        BigDecimal current = ttmAssetTurnover(periods, index);
        BigDecimal prior = ttmAssetTurnover(periods, index - 4);
        if (current == null || prior == null) {
            return null;
        }
        return current.compareTo(prior) > 0;
    }

    private static BigDecimal ttmGrossMargin(List<FinancialPeriodStatement> periods, int index) {
        BigDecimal revenue = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::operatingRevenue);
        BigDecimal cost = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::operatingCost);
        if (revenue == null || cost == null) {
            return null;
        }
        return FinancialTtm.percent(revenue.subtract(cost), revenue);
    }

    private static BigDecimal ttmAssetTurnover(List<FinancialPeriodStatement> periods, int index) {
        BigDecimal revenue = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::operatingRevenue);
        return FinancialTtm.ratio(revenue, averageAssets(periods, index));
    }

    private static BigDecimal averageAssets(List<FinancialPeriodStatement> periods, int index) {
        if (index - 4 < 0) {
            return null;
        }
        BigDecimal current = periods.get(index).totalAssets();
        BigDecimal prior = periods.get(index - 4).totalAssets();
        if (current == null || prior == null) {
            return null;
        }
        return current.add(prior).divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal debtRatio(FinancialPeriodStatement statement) {
        return FinancialTtm.percent(statement.totalLiabilities(), statement.totalAssets());
    }

    private static BigDecimal currentRatio(FinancialPeriodStatement statement) {
        return FinancialTtm.ratio(statement.currentAssets(), statement.currentLiabilities());
    }

    static String tier(int total) {
        if (total <= 2) return "弱";
        if (total <= 5) return "中";
        if (total <= 7) return "良";
        return "优";
    }
}
```

注意:测试里 `history.subset(3)` 需要一个辅助方法——直接在测试类加私有方法:

```java
    // 测试辅助(加进 FinancialQualityScorerTest)
    private static FinancialStatementHistory subset(FinancialStatementHistory history, int size) {
        return new FinancialStatementHistory(history.security(),
                new ArrayList<>(history.periods().subList(0, size)));
    }
```

并把 `history.subset(3)` 改为 `subset(history, 3)`。

- [ ] **Step 5: 运行测试通过** — `'-Dtest=FinancialQualityScorerTest' test`,预期 PASS。

- [ ] **Step 6: 提交(经用户确认后)** — `git add src/main/java/com/astock/agent/analysis/financial/*.java src/test/java/com/astock/agent/analysis/financial/*.java; git commit -m "feat: add TTM-based F-Score financial quality scorer"`

---

### Task 3: 多期趋势引擎

**Files:**
- Create: `src/main/java/com/astock/agent/analysis/financial/FinancialTrendResult.java`
- Create: `src/main/java/com/astock/agent/analysis/financial/FinancialTrendCalculator.java`
- Create: `src/test/java/com/astock/agent/analysis/financial/FinancialTrendCalculatorTest.java`

**Interfaces:**
- Consumes: `FinancialStatementHistory`、`FinancialTtm`
- Produces:
  - `record TrendPoint(LocalDate period, BigDecimal value)`
  - `record TrendSeries(String name, String unit, String caliber, List<TrendPoint> points, List<BigDecimal> yoyGrowthPercent, String direction, BigDecimal acceleration, BigDecimal volatility)`
    - `caliber` 为 `"CUMULATIVE"`(累计)或 `"POINT_IN_TIME"`(时点);`direction` 为 `RISING|FALLING|MIXED|INSUFFICIENT`;`acceleration/volatility` 可为 null。
  - `record FinancialTrendResult(List<TrendSeries> series, int periodCount)`
  - `FinancialTrendCalculator.calculate(FinancialStatementHistory)` → FinancialTrendResult
  - 系列顺序固定:营业总收入、归母净利润、净利润、毛利率、净利率、ROE_TTM、经营现金流、资产负债率

- [ ] **Step 1: 写失败测试**

```java
package com.astock.agent.analysis.financial;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.Exchange;
import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialTrendCalculatorTest {

    private static final String[] PERIODS = {
        "2023-06-30", "2023-09-30", "2023-12-31", "2024-03-31",
        "2024-06-30", "2024-09-30", "2024-12-31", "2025-03-31",
        "2025-06-30", "2025-09-30", "2025-12-31", "2026-03-31",
    };

    private final FinancialTrendCalculator calculator = new FinancialTrendCalculator();

    @Test
    void producesEightSeriesWithFullPeriods() {
        FinancialTrendResult result = calculator.calculate(history());

        assertThat(result.periodCount()).isEqualTo(12);
        assertThat(result.series()).hasSize(8);
        assertThat(result.series().get(0).name()).isEqualTo("营业总收入");
        assertThat(result.series().get(0).caliber()).isEqualTo("CUMULATIVE");
        assertThat(result.series().get(0).points()).hasSize(12);
        assertThat(result.series().get(7).name()).isEqualTo("资产负债率");
        assertThat(result.series().get(7).caliber()).isEqualTo("POINT_IN_TIME");
    }

    @Test
    void yoyGrowthComparesWithSamePeriodLastYear() {
        FinancialTrendResult result = calculator.calculate(history());

        TrendSeries revenue = result.series().get(0);
        assertThat(revenue.yoyGrowthPercent()).hasSize(12);
        assertThat(revenue.yoyGrowthPercent().subList(0, 4)).containsOnlyNulls();
        assertThat(revenue.yoyGrowthPercent().get(11)).isNotNull();
    }

    @Test
    void directionFollowsRecentComparablePairs() {
        FinancialTrendResult result = calculator.calculate(history());

        assertThat(result.series().get(0).direction()).isEqualTo("RISING");
    }

    @Test
    void accelerationIsNullWhenLessThanTwoPairs() {
        FinancialStatementHistory shortHistory = new FinancialStatementHistory(
                new SecurityId("600519", Exchange.SHANGHAI),
                history().periods().subList(0, 6));
        FinancialTrendResult result = calculator.calculate(shortHistory);

        assertThat(result.series().get(0).acceleration()).isNull();
    }

    private static FinancialStatementHistory history() {
        List<FinancialPeriodStatement> periods = new ArrayList<>();
        for (int i = 0; i < PERIODS.length; i++) {
            periods.add(new FinancialPeriodStatement(
                    LocalDate.parse(PERIODS[i]),
                    BigDecimal.valueOf(1000 + i * 50L),
                    BigDecimal.valueOf(350 + i * 10L),
                    BigDecimal.valueOf(120 + i * 8L),
                    BigDecimal.valueOf(110 + i * 8L),
                    BigDecimal.valueOf(300 + i * 20L),
                    BigDecimal.valueOf(5000 + i * 200L),
                    BigDecimal.valueOf(2000 + i * 60L),
                    BigDecimal.valueOf(1500 + i * 40L),
                    BigDecimal.valueOf(800 + i * 30L),
                    BigDecimal.valueOf(500L),
                    BigDecimal.valueOf(2500 + i * 100L)));
        }
        return new FinancialStatementHistory(new SecurityId("600519", Exchange.SHANGHAI), periods);
    }
}
```

- [ ] **Step 2: 运行确认失败** — `'-Dtest=FinancialTrendCalculatorTest' test`,预期编译失败。

- [ ] **Step 3: 实现记录与计算器**

`FinancialTrendResult.java`:

```java
package com.astock.agent.analysis.financial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 多期财务趋势:固定 8 个系列,每个系列含点位、同比、方向、加速度与波动率。 */
public record FinancialTrendResult(List<TrendSeries> series, int periodCount) {

    public FinancialTrendResult {
        series = series == null ? List.of() : List.copyOf(series);
    }

    public record TrendPoint(LocalDate period, BigDecimal value) {
    }

    public record TrendSeries(
            String name,
            String unit,
            String caliber,
            List<TrendPoint> points,
            List<BigDecimal> yoyGrowthPercent,
            String direction,
            BigDecimal acceleration,
            BigDecimal volatility) {
    }
}
```

`FinancialTrendCalculator.java`(终版结构:points 构造与统计组装分离;ROE_TTM 逐期用 TTM 组法):

```java
package com.astock.agent.analysis.financial;

import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 多期趋势计算:固定 8 个系列,同比为当期 vs 上年同期;
 * 方向取最近 3 个可比同比对的符号一致性,加速度取最近两个同比之差,
 * 波动率取可比同比序列标准差。全部为确定性纯函数。
 */
public final class FinancialTrendCalculator {

    public FinancialTrendResult calculate(FinancialStatementHistory history) {
        List<FinancialPeriodStatement> periods = history.periods();
        List<FinancialTrendResult.TrendSeries> series = List.of(
                build("营业总收入", "元", "CUMULATIVE",
                        points(periods, FinancialPeriodStatement::operatingRevenue)),
                build("归母净利润", "元", "CUMULATIVE",
                        points(periods, FinancialPeriodStatement::netProfitAttributable)),
                build("净利润", "元", "CUMULATIVE",
                        points(periods, FinancialPeriodStatement::netProfit)),
                build("毛利率", "%", "CUMULATIVE",
                        points(periods, statement -> margin(statement.operatingRevenue(), statement.operatingCost()))),
                build("净利率", "%", "CUMULATIVE",
                        points(periods, statement -> FinancialTtm.percent(statement.netProfit(), statement.operatingRevenue()))),
                build("ROE_TTM", "%", "CUMULATIVE", roePoints(periods)),
                build("经营现金流", "元", "CUMULATIVE",
                        points(periods, FinancialPeriodStatement::operatingCashFlow)),
                build("资产负债率", "%", "POINT_IN_TIME",
                        points(periods, statement -> FinancialTtm.percent(statement.totalLiabilities(), statement.totalAssets()))));
        return new FinancialTrendResult(series, periods.size());
    }

    private static List<FinancialTrendResult.TrendPoint> points(List<FinancialPeriodStatement> periods,
            Function<FinancialPeriodStatement, BigDecimal> value) {
        List<FinancialTrendResult.TrendPoint> result = new ArrayList<>();
        for (FinancialPeriodStatement period : periods) {
            result.add(new FinancialTrendResult.TrendPoint(period.reportPeriod(), value.apply(period)));
        }
        return result;
    }

    private static List<FinancialTrendResult.TrendPoint> roePoints(List<FinancialPeriodStatement> periods) {
        List<FinancialTrendResult.TrendPoint> result = new ArrayList<>();
        for (int i = 0; i < periods.size(); i++) {
            BigDecimal np = FinancialTtm.ttm(periods, i, FinancialPeriodStatement::netProfitAttributable);
            result.add(new FinancialTrendResult.TrendPoint(periods.get(i).reportPeriod(),
                    FinancialTtm.percent(np, periods.get(i).equityAttributable())));
        }
        return result;
    }

    private static FinancialTrendResult.TrendSeries build(String name, String unit, String caliber,
            List<FinancialTrendResult.TrendPoint> points) {
        List<BigDecimal> yoy = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            yoy.add(yoyGrowth(points, i));
        }
        return new FinancialTrendResult.TrendSeries(name, unit, caliber, List.copyOf(points),
                List.copyOf(yoy), direction(yoy), acceleration(yoy), volatility(yoy));
    }

    private static BigDecimal margin(BigDecimal revenue, BigDecimal cost) {
        if (revenue == null || cost == null) {
            return null;
        }
        return FinancialTtm.percent(revenue.subtract(cost), revenue);
    }

    private static BigDecimal yoyGrowth(List<FinancialTrendResult.TrendPoint> points, int index) {
        if (index < 4) {
            return null;
        }
        BigDecimal current = points.get(index).value();
        BigDecimal prior = points.get(index - 4).value();
        if (current == null || prior == null || prior.signum() == 0) {
            return null;
        }
        return current.subtract(prior)
                .divide(prior, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static String direction(List<BigDecimal> yoy) {
        List<BigDecimal> recent = new ArrayList<>();
        for (int i = yoy.size() - 1; i >= 0 && recent.size() < 3; i--) {
            if (yoy.get(i) != null) {
                recent.add(yoy.get(i));
            }
        }
        if (recent.isEmpty()) {
            return "INSUFFICIENT";
        }
        boolean allPositive = recent.stream().allMatch(value -> value.signum() > 0);
        boolean allNegative = recent.stream().allMatch(value -> value.signum() < 0);
        if (allPositive) return "RISING";
        if (allNegative) return "FALLING";
        return "MIXED";
    }

    private static BigDecimal acceleration(List<BigDecimal> yoy) {
        BigDecimal latest = nthNonNullFromEnd(yoy, 2);
        BigDecimal previous = nthNonNullFromEnd(yoy, 3);
        if (latest == null || previous == null) {
            return null;
        }
        return latest.subtract(previous).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nthNonNullFromEnd(List<BigDecimal> yoy, int n) {
        int count = 0;
        for (int i = yoy.size() - 1; i >= 0; i--) {
            if (yoy.get(i) != null && ++count == n) {
                return yoy.get(i);
            }
        }
        return null;
    }

    private static BigDecimal volatility(List<BigDecimal> yoy) {
        List<BigDecimal> available = yoy.stream().filter(value -> value != null).toList();
        if (available.size() < 2) {
            return null;
        }
        BigDecimal mean = available.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(available.size()), MathContext.DECIMAL64);
        BigDecimal variance = available.stream()
                .map(value -> value.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(available.size()), MathContext.DECIMAL64);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: 运行测试通过** — `'-Dtest=FinancialTrendCalculatorTest' test`,预期 PASS。

- [ ] **Step 5: 提交(经用户确认后)** — `git commit -m "feat: add multi-period financial trend calculator"`

---

### Task 4: 证据包 + 叙事草稿 + 校验器 + 确定性组装器

**Files:**
- Create: `src/main/java/com/astock/agent/agent/financial/FinancialEvidencePackage.java`
- Create: `src/main/java/com/astock/agent/agent/financial/FinancialNarrativeDraft.java`
- Create: `src/main/java/com/astock/agent/agent/financial/FinancialNarrative.java`
- Create: `src/main/java/com/astock/agent/agent/financial/FinancialReportValidator.java`
- Create: `src/main/java/com/astock/agent/agent/financial/FinancialDeterministicComposer.java`
- Create: `src/test/java/com/astock/agent/agent/financial/FinancialReportValidatorTest.java`
- Create: `src/test/java/com/astock/agent/agent/financial/FinancialDeterministicComposerTest.java`

**Interfaces:**
- Consumes: `FinancialStatementHistory`、`FinancialQualityScore`、`FinancialTrendResult`
- Produces:
  - `record FinancialEvidencePackage(String securityCode, FinancialStatementHistory history, FinancialQualityScore qualityScore, FinancialTrendResult trends, boolean financialIndustry, int passCount, int failCount, int unverifiedCount, int signalCount)`
  - `record FinancialNarrativeDraft(String tierInterpretation, String signalCommentary, String trendCommentary, String riskNotes, String disclaimer)`
  - `record FinancialNarrative(String tierInterpretation, String signalCommentary, String trendCommentary, String riskNotes)`
  - `FinancialReportValidator.validate(FinancialNarrativeDraft draft, FinancialEvidencePackage pack)` → `Validation(List<String> issues)`;`Validation.blocking()`
  - 问题码:`EMPTY_REQUIRED_SECTION`、`INVALID_DISCLAIMER`、`TRADE_INSTRUCTION`、`UNSUPPORTED_NUMBER`、`MISSING_TIER_REFERENCE`、`NARRATIVE_TOO_LONG`
  - `FinancialDeterministicComposer.compose(FinancialEvidencePackage)` → FinancialNarrative
  - 常量 `FinancialDeterministicComposer.REQUIRED_DISCLAIMER = "仅供学习研究，不构成投资建议"`

- [ ] **Step 1: 写失败测试(校验器)**

```java
package com.astock.agent.agent.financial;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialTrendResult;
import com.astock.agent.marketdata.model.Exchange;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SecurityId;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialReportValidatorTest {

    private final FinancialReportValidator validator = new FinancialReportValidator();
    private final FinancialEvidencePackage pack = new FinancialEvidencePackage(
            "600519",
            new FinancialStatementHistory(new SecurityId("600519", Exchange.SHANGHAI), List.of()),
            new FinancialQualityScore(6, "良", 9, List.of(), true),
            new FinancialTrendResult(List.of(), 12),
            false, 6, 3, 0, 9);

    @Test
    void validDraftPasses() {
        FinancialReportValidator.Validation validation = validator.validate(
                new FinancialNarrativeDraft(
                        "该证券财务质量 F-Score 为 6 分，档位 良，评估 9 个信号。",
                        "盈利与现金流信号通过；毛利率与周转率信号未通过。",
                        "营业总收入同比持续上升，趋势方向为上升。",
                        "需注意样本期内数据完整性与行业口径限制。",
                        FinancialDeterministicComposer.REQUIRED_DISCLAIMER),
                pack);

        assertThat(validation.blocking()).isFalse();
    }

    @Test
    void fabricatedNumberIsRejected() {
        FinancialReportValidator.Validation validation = validator.validate(
                new FinancialNarrativeDraft(
                        "F-Score 为 6 分，档位 良。",
                        "净利润 999 亿元。",
                        "营收稳定。",
                        "无风险。",
                        FinancialDeterministicComposer.REQUIRED_DISCLAIMER),
                pack);

        assertThat(validation.issues()).contains("UNSUPPORTED_NUMBER");
    }

    @Test
    void missingTierReferenceIsRejected() {
        FinancialReportValidator.Validation validation = validator.validate(
                new FinancialNarrativeDraft(
                        "F-Score 为 6 分。",
                        "信号正常。",
                        "趋势正常。",
                        "无风险。",
                        FinancialDeterministicComposer.REQUIRED_DISCLAIMER),
                pack);

        assertThat(validation.issues()).contains("MISSING_TIER_REFERENCE");
    }

    @Test
    void tradeInstructionAndWrongDisclaimerAreRejected() {
        FinancialReportValidator.Validation validation = validator.validate(
                new FinancialNarrativeDraft(
                        "F-Score 为 6 分，档位 良，建议买入。",
                        "信号正常。",
                        "趋势正常。",
                        "无风险。",
                        "随便写的免责声明"),
                pack);

        assertThat(validation.issues()).contains("TRADE_INSTRUCTION", "INVALID_DISCLAIMER");
    }

    @Test
    void insufficientDataRequiresInsufficientMention() {
        FinancialEvidencePackage insufficient = new FinancialEvidencePackage(
                "600519", pack.history(),
                new FinancialQualityScore(0, "数据不足", 0, List.of(), false),
                new FinancialTrendResult(List.of(), 3), false, 0, 0, 0, 0);
        FinancialReportValidator.Validation validation = validator.validate(
                new FinancialNarrativeDraft("档位 良。", "信号正常。", "趋势正常。", "无风险。",
                        FinancialDeterministicComposer.REQUIRED_DISCLAIMER),
                insufficient);

        assertThat(validation.issues()).contains("MISSING_TIER_REFERENCE");
    }
}
```

注意:pack 里 `passCount=6, failCount=3` 提供数字 6/3/9,使合法草稿中的数字可通过 supported-number 校验。

- [ ] **Step 2: 运行确认失败** — `'-Dtest=FinancialReportValidatorTest' test`,预期编译失败。

- [ ] **Step 3: 实现记录与证据包/草稿/叙事记录**

`FinancialEvidencePackage.java`:

```java
package com.astock.agent.agent.financial;

import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialTrendResult;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import java.util.Objects;

/** 给模型的唯一有界事实边界:评分、信号、趋势、来源与限制。 */
public record FinancialEvidencePackage(
        String securityCode,
        FinancialStatementHistory history,
        FinancialQualityScore qualityScore,
        FinancialTrendResult trends,
        boolean financialIndustry,
        int passCount,
        int failCount,
        int unverifiedCount,
        int signalCount) {

    public FinancialEvidencePackage {
        Objects.requireNonNull(history, "history is required");
        Objects.requireNonNull(qualityScore, "qualityScore is required");
        Objects.requireNonNull(trends, "trends is required");
    }
}
```

`FinancialNarrativeDraft.java`:

```java
package com.astock.agent.agent.financial;

/** 模型生成的受限叙事草稿;四段中文文本 + 固定免责声明。 */
public record FinancialNarrativeDraft(
        String tierInterpretation,
        String signalCommentary,
        String trendCommentary,
        String riskNotes,
        String disclaimer) {
}
```

`FinancialNarrative.java`:

```java
package com.astock.agent.agent.financial;

/** 校验通过的最终叙事文本(不含生成模式,模式在 FinancialReportAnalysis 上)。 */
public record FinancialNarrative(
        String tierInterpretation,
        String signalCommentary,
        String trendCommentary,
        String riskNotes) {
}
```

- [ ] **Step 4: 实现校验器**(复用 overall 的正则思路)

```java
package com.astock.agent.agent.financial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 财务叙事校验:证据边界、档位引用、交易指令、免责声明与长度。 */
public final class FinancialReportValidator {

    private static final Pattern TRADE = Pattern.compile(
            "买入|卖出|加仓|减仓|建仓|清仓|止盈|止损|目标价|保证收益|收益保证|稳赚"
                    + "|仓位(?:控制|调整|建议|保持|不超过|达到)|(?:建议|控制|调整|提高|降低|维持)[^。！？；\\n]{0,8}仓位");
    private static final Pattern NEGATED_TRADE_PREFIX = Pattern.compile(
            "(?:不建议|不推荐|不提供|不构成|不进行|不采取|不执行|不作出|不做|不含|不应|不宜|不能|不得|禁止|避免|并非|没有|暂无|未给出|未提供|未形成|不买入|不卖出|请勿|不可|无法|不允许|不涉及|不代表|不意味着)[^。！？；\\n]{0,8}$");
    private static final Pattern NEGATED_TRADE_SUFFIX = Pattern.compile(
            "^[^。！？；\\n]{0,8}(?:不可用|不可得|不提供|不构成|不代表|不属于|不存在|未提供|未形成|不适用|不涉及|并非|不是|无关)$");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])[-+]?\\d+(?:\\.\\d+)?%?");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public Validation validate(FinancialNarrativeDraft draft, FinancialEvidencePackage pack) {
        List<String> issues = new ArrayList<>();
        if (draft == null) {
            issues.add("EMPTY_REQUIRED_SECTION");
            return new Validation(issues);
        }
        if (blank(draft.tierInterpretation()) || blank(draft.signalCommentary())
                || blank(draft.trendCommentary()) || blank(draft.riskNotes())) {
            add(issues, "EMPTY_REQUIRED_SECTION");
        }
        if (!FinancialDeterministicComposer.REQUIRED_DISCLAIMER.equals(draft.disclaimer())) {
            add(issues, "INVALID_DISCLAIMER");
        }
        String text = String.join(" ", draft.tierInterpretation(), draft.signalCommentary(),
                draft.trendCommentary(), draft.riskNotes(), draft.disclaimer());
        if (text.codePointCount(0, text.length()) > 2000) {
            add(issues, "NARRATIVE_TOO_LONG");
        }
        if (containsTradeInstruction(text)) {
            add(issues, "TRADE_INSTRUCTION");
        }
        validateTierReference(draft.tierInterpretation(), pack, issues);
        validateNumbers(text, pack, issues);
        return new Validation(issues);
    }

    private void validateTierReference(String tierInterpretation,
            FinancialEvidencePackage pack, List<String> issues) {
        if (tierInterpretation == null) {
            return;
        }
        String required = pack.qualityScore().sufficientData()
                ? pack.qualityScore().tier() : "数据不足";
        if (!tierInterpretation.contains(required)) {
            add(issues, "MISSING_TIER_REFERENCE");
        }
    }

    private void validateNumbers(String text, FinancialEvidencePackage pack, List<String> issues) {
        Set<String> supported = packageNumbers(pack);
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            if (!supported.contains(normalizeNumber(matcher.group()))) {
                add(issues, "UNSUPPORTED_NUMBER");
            }
        }
    }

    private Set<String> packageNumbers(FinancialEvidencePackage pack) {
        Set<String> values = new HashSet<>();
        try {
            JsonNode root = MAPPER.valueToTree(pack);
            collectNumbers(root, values);
        } catch (Exception ignored) {
            return Set.of();
        }
        return values;
    }

    private void collectNumbers(JsonNode node, Set<String> values) {
        if (node == null) {
            return;
        }
        if (node.isNumber()) {
            values.add(normalizeNumber(node.asText()));
            return;
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> collectNumbers(child, values));
        }
    }

    private boolean containsTradeInstruction(String text) {
        Matcher matcher = TRADE.matcher(text);
        while (matcher.find()) {
            int prefixStart = Math.max(0, matcher.start() - 16);
            String prefix = text.substring(prefixStart, matcher.start());
            if (NEGATED_TRADE_PREFIX.matcher(prefix).find()) {
                continue;
            }
            int suffixEnd = Math.min(text.length(), matcher.end() + 16);
            String suffix = text.substring(matcher.end(), suffixEnd);
            if (NEGATED_TRADE_SUFFIX.matcher(suffix).find()) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static String normalizeNumber(String value) {
        String token = value.replace("%", "");
        try {
            return new BigDecimal(token).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return token;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void add(List<String> issues, String issue) {
        if (!issues.contains(issue)) {
            issues.add(issue);
        }
    }

    public record Validation(List<String> issues) {
        public Validation {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean blocking() {
            return !issues.isEmpty();
        }
    }
}
```

- [ ] **Step 5: 实现确定性组装器 + 测试**

`FinancialDeterministicComposer.java`:

```java
package com.astock.agent.agent.financial;

import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialTrendResult;
import java.util.List;
import java.util.stream.Collectors;

/** 模型不可用或校验失败时的确定性文字组装:全部文字来自证据包。 */
public final class FinancialDeterministicComposer {

    public static final String REQUIRED_DISCLAIMER = "仅供学习研究，不构成投资建议";

    public FinancialNarrative compose(FinancialEvidencePackage pack) {
        FinancialQualityScore score = pack.qualityScore();
        String tierText;
        if (score.sufficientData()) {
            tierText = "按确定性规则计算，" + pack.securityCode()
                    + " 财务质量 F-Score 为 " + score.total() + " 分，档位 " + score.tier()
                    + "（0-2 弱 / 3-5 中 / 6-7 良 / 8-9 优），共评估 "
                    + score.evaluatedSignals() + " 个信号。";
        } else {
            tierText = "报告期不足 4 期，无法计算财务质量评分，当前状态为数据不足。";
        }
        String signals = signalText(pack);
        String trends = trendText(pack);
        String risks = riskText(pack);
        return new FinancialNarrative(tierText, signals, trends, risks);
    }

    private String signalText(FinancialEvidencePackage pack) {
        List<String> lines = pack.qualityScore().signals().stream()
                .map(signal -> signal.number() + " " + signal.name() + "："
                        + statusText(signal.status()) + "。" + signal.evidence())
                .collect(Collectors.toList());
        return lines.isEmpty() ? "当前没有可评估的财务质量信号。" : String.join(" ", lines);
    }

    private String trendText(FinancialEvidencePackage pack) {
        List<String> lines = pack.trends().series().stream()
                .map(series -> series.name() + " 方向为 " + directionText(series.direction()))
                .collect(Collectors.toList());
        return lines.isEmpty() ? "当前没有可展示的趋势序列。" : String.join(" ", lines);
    }

    private String riskText(FinancialEvidencePackage pack) {
        List<String> risks = new java.util.ArrayList<>();
        if (pack.unverifiedCount() > 0) {
            risks.add(pack.unverifiedCount() + " 个信号因字段或历史期数不足无法评估，评分结论受数据完整性限制。");
        }
        if (pack.financialIndustry()) {
            risks.add("该公司属于金融行业，毛利率与资产周转率信号不适用传统口径。");
        }
        risks.add("趋势基于报告期累计口径，同比为当期与上年同期比较。");
        return String.join(" ", risks);
    }

    private static String statusText(FinancialQualityScore.SignalStatus status) {
        return switch (status) {
            case PASS -> "通过";
            case FAIL -> "未通过";
            case UNVERIFIED -> "无法评估";
        };
    }

    private static String directionText(String direction) {
        return switch (direction) {
            case "RISING" -> "上升";
            case "FALLING" -> "下降";
            case "MIXED" -> "波动";
            default -> "样本不足";
        };
    }
}
```

`FinancialDeterministicComposerTest.java`:

```java
package com.astock.agent.agent.financial;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialTrendResult;
import com.astock.agent.analysis.financial.FinancialTrendResult.TrendSeries;
import com.astock.agent.marketdata.model.Exchange;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialDeterministicComposerTest {

    private final FinancialDeterministicComposer composer = new FinancialDeterministicComposer();

    @Test
    void fallbackTextContainsScoreTierAndSignals() {
        FinancialEvidencePackage pack = new FinancialEvidencePackage(
                "600519",
                new FinancialStatementHistory(new SecurityId("600519", Exchange.SHANGHAI), List.of()),
                new FinancialQualityScore(6, "良", 9, List.of(
                        new FinancialQualityScore.SignalResult(1, "TTM ROA 为正",
                                FinancialQualityScore.SignalStatus.PASS, "条件成立")), true),
                new FinancialTrendResult(List.of(new TrendSeries("营业总收入", "元", "CUMULATIVE",
                        List.of(new FinancialTrendResult.TrendPoint(LocalDate.parse("2026-06-30"), BigDecimal.valueOf(1))),
                        List.of(), "RISING", null, null)), 12),
                false, 6, 3, 0, 9);

        FinancialNarrative narrative = composer.compose(pack);

        assertThat(narrative.tierInterpretation()).contains("6 分").contains("良");
        assertThat(narrative.signalCommentary()).contains("通过");
        assertThat(narrative.trendCommentary()).contains("上升");
        assertThat(narrative.riskNotes()).isNotEmpty();
    }

    @Test
    void insufficientHistoryProducesInsufficientText() {
        FinancialEvidencePackage pack = new FinancialEvidencePackage(
                "600519",
                new FinancialStatementHistory(new SecurityId("600519", Exchange.SHANGHAI), List.of()),
                FinancialQualityScore.insufficient(),
                new FinancialTrendResult(List.of(), 3),
                false, 0, 0, 0, 0);

        FinancialNarrative narrative = composer.compose(pack);

        assertThat(narrative.tierInterpretation()).contains("数据不足");
    }
}
```

- [ ] **Step 6: 运行测试通过** — `'-Dtest=FinancialReportValidatorTest,FinancialDeterministicComposerTest' test`,预期 PASS。

- [ ] **Step 7: 提交(经用户确认后)** — `git commit -m "feat: add financial narrative validator and deterministic composer"`

---

### Task 5: DeepSeek 生成器 + 编排服务 + 配置装配

**Files:**
- Create: `src/main/java/com/astock/agent/agent/financial/FinancialReportGenerator.java`
- Create: `src/main/java/com/astock/agent/agent/financial/SpringAiFinancialNarrativeGenerator.java`
- Create: `src/main/java/com/astock/agent/agent/financial/FinancialReportAnalysis.java`
- Create: `src/main/java/com/astock/agent/agent/financial/FinancialReportService.java`
- Create: `src/main/java/com/astock/agent/analysis/FinancialDataUnavailableException.java`
- Modify: `src/main/java/com/astock/agent/agent/AgentConfiguration.java`(bean)
- Modify: `src/main/java/com/astock/agent/config/CacheConfiguration.java`(cache bean)
- Modify: `src/main/resources/application.yml`(角色)
- Create: `src/test/java/com/astock/agent/agent/financial/FinancialReportServiceTest.java`

**Interfaces:**
- Consumes: `NamedChatClientRegistry`、`StockAgentTools`、`ResearchGateway`、`financialHistoryCache`、Task 2-4 类型
- Produces:
  - `interface FinancialReportGenerator { FinancialNarrativeDraft generate(FinancialEvidencePackage) throws Exception; FinancialNarrativeDraft repair(FinancialEvidencePackage, FinancialNarrativeDraft, List<String>) throws Exception; String modelName(); }`
  - `SpringAiFinancialNarrativeGenerator.PROMPT_VERSION = "financial-v1"`
  - `record FinancialReportAnalysis(String securityCode, String reportPeriodRange, int periodCount, FinancialQualityScore qualityScore, FinancialTrendResult trends, FinancialNarrative narrative, GenerationMode generationMode, ModelDiagnostic diagnostic, boolean financialIndustry, String generatedAt, String ruleVersion, String promptVersion, String disclaimer)` + 常量 `REQUIRED_DISCLAIMER`
  - `FinancialReportService.generate(String code)` → FinancialReportAnalysis;角色常量 `"financial-report"`
  - `class FinancialDataUnavailableException extends RuntimeException`

- [ ] **Step 1: 写失败测试(服务编排)**

```java
package com.astock.agent.agent.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.astock.agent.analysis.FinancialDataUnavailableException;
import com.astock.agent.analysis.ResearchGateway;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.agent.report.GenerationMode;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.Exchange;
import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SecurityId;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class FinancialReportServiceTest {

    @Test
    void missingModelFallsBackDeterministically() {
        FinancialReportService service = new FinancialReportService(
                gateway(history(12)),
                tools(),
                new NamedChatClientRegistry(Map.of(), Map.of("financial-report", "missing")),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> { throw new AssertionError("无模型时不得构造生成器"); });

        FinancialReportAnalysis analysis = service.generate("600519");

        assertThat(analysis.generationMode()).isEqualTo(GenerationMode.DETERMINISTIC_FALLBACK);
        assertThat(analysis.qualityScore().sufficientData()).isTrue();
        assertThat(analysis.narrative().tierInterpretation()).contains("良");
    }

    @Test
    void validModelDraftReturnsModelAssisted() {
        FinancialReportService service = new FinancialReportService(
                gateway(history(12)),
                tools(),
                new NamedChatClientRegistry(
                        Map.of("deepseek", new NamedChatClientRegistry.NamedModel(
                                mock(ChatClient.class), "deepseek-chat")),
                        Map.of("financial-report", "deepseek")),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> new FinancialReportGenerator() {
                    @Override
                    public FinancialNarrativeDraft generate(FinancialEvidencePackage pack) {
                        return new FinancialNarrativeDraft(
                                "F-Score 为 " + pack.qualityScore().total() + " 分，档位 "
                                        + pack.qualityScore().tier() + "。",
                                "盈利信号通过。", "营收趋势上升。", "注意数据完整性限制。",
                                FinancialDeterministicComposer.REQUIRED_DISCLAIMER);
                    }

                    @Override
                    public FinancialNarrativeDraft repair(FinancialEvidencePackage pack,
                            FinancialNarrativeDraft draft, List<String> issues) {
                        throw new AssertionError("合法草稿不得触发修复");
                    }

                    @Override
                    public String modelName() {
                        return "deepseek-chat";
                    }
                });

        FinancialReportAnalysis analysis = service.generate("600519");

        assertThat(analysis.generationMode()).isEqualTo(GenerationMode.MODEL_ASSISTED);
        assertThat(analysis.diagnostic()).isNull();
    }

    @Test
    void invalidDraftIsRepairedOnceThenAccepted() {
        AtomicInteger repairs = new AtomicInteger();
        FinancialReportService service = new FinancialReportService(
                gateway(history(12)),
                tools(),
                new NamedChatClientRegistry(
                        Map.of("deepseek", new NamedChatClientRegistry.NamedModel(
                                mock(ChatClient.class), "deepseek-chat")),
                        Map.of("financial-report", "deepseek")),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> new FinancialReportGenerator() {
                    @Override
                    public FinancialNarrativeDraft generate(FinancialEvidencePackage pack) {
                        return new FinancialNarrativeDraft("F-Score 为 "
                                + pack.qualityScore().total() + " 分，档位 " + pack.qualityScore().tier() + "。",
                                "建议买入。", "营收趋势上升。", "注意限制。", "错误免责");
                    }

                    @Override
                    public FinancialNarrativeDraft repair(FinancialEvidencePackage pack,
                            FinancialNarrativeDraft draft, List<String> issues) {
                        repairs.incrementAndGet();
                        return new FinancialNarrativeDraft(
                                "F-Score 为 " + pack.qualityScore().total() + " 分，档位 "
                                        + pack.qualityScore().tier() + "。",
                                "盈利信号通过。", "营收趋势上升。", "注意数据完整性限制。",
                                FinancialDeterministicComposer.REQUIRED_DISCLAIMER);
                    }

                    @Override
                    public String modelName() {
                        return "deepseek-chat";
                    }
                });

        FinancialReportAnalysis analysis = service.generate("600519");

        assertThat(repairs).hasValue(1);
        assertThat(analysis.generationMode()).isEqualTo(GenerationMode.MODEL_ASSISTED);
    }

    @Test
    void modelExceptionReturnsDeterministicFallbackWithDiagnostic() {
        FinancialReportService service = new FinancialReportService(
                gateway(history(12)),
                tools(),
                new NamedChatClientRegistry(
                        Map.of("deepseek", new NamedChatClientRegistry.NamedModel(
                                mock(ChatClient.class), "deepseek-chat")),
                        Map.of("financial-report", "deepseek")),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> new FinancialReportGenerator() {
                    @Override
                    public FinancialNarrativeDraft generate(FinancialEvidencePackage pack) {
                        throw new IllegalStateException("model request failed");
                    }

                    @Override
                    public FinancialNarrativeDraft repair(FinancialEvidencePackage pack,
                            FinancialNarrativeDraft draft, List<String> issues) {
                        throw new AssertionError("异常时不得触发修复");
                    }

                    @Override
                    public String modelName() {
                        return "deepseek-chat";
                    }
                });

        FinancialReportAnalysis analysis = service.generate("600519");

        assertThat(analysis.generationMode()).isEqualTo(GenerationMode.DETERMINISTIC_FALLBACK);
        assertThat(analysis.diagnostic()).isNotNull();
        assertThat(analysis.diagnostic().errorCode()).isEqualTo("MODEL_REQUEST_FAILED");
    }

    @Test
    void unavailableHistoryThrowsFinancialDataUnavailable() {
        FinancialReportService service = new FinancialReportService(
                id -> DataSection.unavailable("Sina statements failed"),
                tools(),
                new NamedChatClientRegistry(Map.of(), Map.of()),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> { throw new AssertionError("无模型时不得构造生成器"); });

        assertThatThrownBy(() -> service.generate("600519"))
                .isInstanceOf(FinancialDataUnavailableException.class);
    }

    @Test
    void insufficientPeriodsStillReturnsResponse() {
        FinancialReportService service = new FinancialReportService(
                gateway(history(3)),
                tools(),
                new NamedChatClientRegistry(Map.of(), Map.of()),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> { throw new AssertionError("无模型时不得构造生成器"); });

        FinancialReportAnalysis analysis = service.generate("600519");

        assertThat(analysis.generationMode()).isEqualTo(GenerationMode.DETERMINISTIC_FALLBACK);
        assertThat(analysis.qualityScore().sufficientData()).isFalse();
    }

    private static com.astock.agent.agent.StockAgentTools tools() {
        return new com.astock.agent.agent.StockAgentTools(id ->
                StockResearchSnapshot.empty(SecurityId.parse("600519")));
    }

    private static ResearchGateway gateway(FinancialStatementHistory history) {
        return new ResearchGateway() {
            @Override
            public DataSection<FinancialStatementHistory> financialHistory(SecurityId security) {
                return DataSection.healthy(history, null);
            }

            @Override public DataSection<com.astock.agent.marketdata.model.Quote> quote(SecurityId s) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<List<com.astock.agent.marketdata.model.DailyBar>> bars(SecurityId s) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<List<com.astock.agent.marketdata.model.DailyBar>> crossCheckBars(SecurityId s) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> sectors(SecurityId s) { return DataSection.unavailable("stub"); }
            @Override public DataSection<com.astock.agent.marketdata.model.IndustryValuationData> industryValuation(SecurityId s) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> fundFlow(SecurityId s) { return DataSection.unavailable("stub"); }
            @Override public DataSection<?> capital(SecurityId s) { return DataSection.unavailable("stub"); }
            @Override public DataSection<?> fundamentals(SecurityId s) { return DataSection.unavailable("stub"); }
            @Override public DataSection<?> research(SecurityId s) { return DataSection.unavailable("stub"); }
            @Override public DataSection<?> news(SecurityId s) { return DataSection.unavailable("stub"); }
            @Override public DataSection<?> announcements(SecurityId s) { return DataSection.unavailable("stub"); }
        };
    }

    private static FinancialStatementHistory history(int size) {
        java.util.ArrayList<FinancialPeriodStatement> periods = new java.util.ArrayList<>();
        for (int i = 0; i < size; i++) {
            periods.add(new FinancialPeriodStatement(
                    LocalDate.of(2023 + (i + 1) / 4, ((i + 1) % 4) * 3, 30),
                    BigDecimal.valueOf(1000 + i * 50L), BigDecimal.valueOf(350 + i * 10L),
                    BigDecimal.valueOf(120 + i * 8L), BigDecimal.valueOf(110 + i * 8L),
                    BigDecimal.valueOf(300 + i * 20L), BigDecimal.valueOf(5000 + i * 200L),
                    BigDecimal.valueOf(2000 + i * 60L), BigDecimal.valueOf(1500 + i * 40L),
                    BigDecimal.valueOf(800 + i * 30L), BigDecimal.valueOf(500L),
                    BigDecimal.valueOf(2500 + i * 100L)));
        }
        return new FinancialStatementHistory(new SecurityId("600519", Exchange.SHANGHAI), periods);
    }
}
```

注意:上述测试用了 `DataSection.healthy(history, null)`——检查 `DataSection.healthy` 是否允许 null provenance(若不允许,则构造 `new Provenance("Sina Finance", URI.create("https://finance.sina.com.cn/"), null, Instant.now(), false, null)`)。执行时以编译结果为准。

- [ ] **Step 2: 运行确认失败** — `'-Dtest=FinancialReportServiceTest' test`,预期编译失败。

- [ ] **Step 3: 实现生成器接口 + Spring AI 生成器**

`FinancialReportGenerator.java`:

```java
package com.astock.agent.agent.financial;

import java.util.List;

/** 财务叙事生成器:只写叙事,不接触数据或计算。 */
public interface FinancialReportGenerator {

    FinancialNarrativeDraft generate(FinancialEvidencePackage pack) throws Exception;

    FinancialNarrativeDraft repair(FinancialEvidencePackage pack,
            FinancialNarrativeDraft draft, List<String> issues) throws Exception;

    String modelName();
}
```

`SpringAiFinancialNarrativeGenerator.java`(镜像 SpringAiOverallReportGenerator):

```java
package com.astock.agent.agent.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 面向 OpenAI 兼容接口的财务叙事适配器(DeepSeek)。
 *
 * <p>只负责 Prompt 与结构化实体映射;事实全部来自 {@link FinancialEvidencePackage},
 * 结果必须经过 {@link FinancialReportValidator} 校验。</p>
 */
public final class SpringAiFinancialNarrativeGenerator implements FinancialReportGenerator {

    public static final String PROMPT_VERSION = "financial-v1";

    public static final String SYSTEM_PROMPT = """
            你是一名严谨的 A 股财务分析助理。下面的 JSON 是唯一事实边界：财务质量评分(F-Score 0-9)、
            9 个信号明细、多期趋势序列与统计，全部由确定性规则计算。只能使用该 JSON 中出现的数字、
            时期、状态与证据，不得联网、调用工具、猜测或补写任何额外数字与结论。
            UNVERIFIED 表示字段或历史期数不足，只能如实说明"无法评估"，不能当作失败或通过。

            请生成 FinancialNarrativeDraft JSON，只包含以下字段：
            tierInterpretation：解读评分档位（必须逐字包含档位文字 弱/中/良/优 或 数据不足），
                并给出与档位对应的确定性规则说明（0-2 弱 / 3-5 中 / 6-7 良 / 8-9 优）。
            signalCommentary：按信号逐个归因，只引用 JSON 中 signals 的状态与 evidence，不新增数字。
            trendCommentary：按趋势序列描述方向与同比变化，只引用 JSON 中 trends 的数值与方向。
            riskNotes：风险与限制，包括 UNVERIFIED 信号数量、金融行业口径限制、累计口径说明。
            disclaimer：必须逐字为：仅供学习研究，不构成投资建议

            不得输出买入、卖出、加仓、减仓、仓位、止盈、止损、目标价、保证收益、收益保证、稳赚等
            交易指令或个性化投资建议；限制说明统一写成"仅作研究，不提供操作建议"。
            不要输出 JSON 之外的数字、百分比、日期或年份。不要 Markdown、不要代码围栏、不要额外字段。
            """;

    private static final String REPAIR_SYSTEM_PROMPT = SYSTEM_PROMPT + """

            这是一次且仅一次的校验修复。只修复问题列表指出的字段，保留其余内容，
            仍然只输出一个完整可解析的 FinancialNarrativeDraft JSON。
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @FunctionalInterface
    interface ModelInvoker {
        FinancialNarrativeDraft invoke(String systemPrompt, String userPrompt) throws Exception;
    }

    private final ModelInvoker invoker;
    private final String modelName;

    public SpringAiFinancialNarrativeGenerator(ChatClient client, String modelName) {
        this((systemPrompt, userPrompt) -> client.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .entity(FinancialNarrativeDraft.class), modelName);
        Objects.requireNonNull(client, "client is required");
    }

    SpringAiFinancialNarrativeGenerator(ModelInvoker invoker, String modelName) {
        this.invoker = Objects.requireNonNull(invoker, "invoker is required");
        this.modelName = modelName == null || modelName.isBlank()
                ? "configured-financial-model" : modelName.trim();
    }

    @Override
    public FinancialNarrativeDraft generate(FinancialEvidencePackage pack) throws Exception {
        Objects.requireNonNull(pack, "pack is required");
        return invoker.invoke(SYSTEM_PROMPT, generationPrompt(pack));
    }

    @Override
    public FinancialNarrativeDraft repair(FinancialEvidencePackage pack,
            FinancialNarrativeDraft draft, java.util.List<String> issues) throws Exception {
        Objects.requireNonNull(pack, "pack is required");
        Objects.requireNonNull(draft, "draft is required");
        java.util.List<String> safeIssues = issues == null ? java.util.List.of()
                : java.util.List.copyOf(issues);
        String userPrompt = "以下是财务证据包 JSON：\n" + MAPPER.writeValueAsString(pack)
                + "\n\n这是上一次草稿 JSON：\n" + MAPPER.writeValueAsString(draft)
                + "\n\n确定性校验发现的问题列表：\n" + MAPPER.writeValueAsString(safeIssues)
                + "\n\n请只修复上述问题，返回完整可解析的 FinancialNarrativeDraft JSON。";
        return invoker.invoke(REPAIR_SYSTEM_PROMPT, userPrompt);
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private static String generationPrompt(FinancialEvidencePackage pack) throws Exception {
        return "以下是证券 " + pack.securityCode() + " 的财务证据包。请严格按照系统约束生成"
                + " FinancialNarrativeDraft JSON：\n" + MAPPER.writeValueAsString(pack);
    }
}
```

- [ ] **Step 4: 实现响应记录 + 异常 + 服务**

`FinancialReportAnalysis.java`:

```java
package com.astock.agent.agent.financial;

import com.astock.agent.agent.report.GenerationMode;
import com.astock.agent.agent.report.ModelDiagnostic;
import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialTrendResult;

/** 财报分析最终响应:评分、趋势、叙事、生成模式与诊断。 */
public record FinancialReportAnalysis(
        String securityCode,
        String reportPeriodRange,
        int periodCount,
        FinancialQualityScore qualityScore,
        FinancialTrendResult trends,
        FinancialNarrative narrative,
        GenerationMode generationMode,
        ModelDiagnostic diagnostic,
        boolean financialIndustry,
        String generatedAt,
        String ruleVersion,
        String promptVersion,
        String disclaimer) {

    public static final String REQUIRED_DISCLAIMER = "仅供学习研究，不构成投资建议";
}
```

`FinancialDataUnavailableException.java`(`analysis` 包):

```java
package com.astock.agent.analysis;

/** 财报历史数据源不可用时抛出;由 web 层转换为 RFC 9457 问题详情。 */
public final class FinancialDataUnavailableException extends RuntimeException {

    public FinancialDataUnavailableException(String message) {
        super(message);
    }
}
```

`FinancialReportService.java`:

```java
package com.astock.agent.agent.financial;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.agent.report.GenerationMode;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.agent.report.ModelDiagnostic;
import com.astock.agent.analysis.FinancialDataUnavailableException;
import com.astock.agent.analysis.ResearchGateway;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialQualityScorer;
import com.astock.agent.analysis.financial.FinancialTrendCalculator;
import com.astock.agent.analysis.financial.FinancialTrendResult;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SectionStatus;
import com.astock.agent.marketdata.model.SecurityId;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 财报分析编排:历史 -> 评分/趋势 -> 证据包 -> 模型叙事 -> 校验 -> 修复一次 -> 确定性回退。
 */
public final class FinancialReportService {

    static final String ROLE = "financial-report";

    @FunctionalInterface
    interface GeneratorFactory {
        FinancialReportGenerator create(NamedChatClientRegistry.NamedModel model);
    }

    private final ResearchGateway gateway;
    private final StockAgentTools tools;
    private final NamedChatClientRegistry registry;
    private final Cache<SecurityId, DataSection<FinancialStatementHistory>> historyCache;
    private final FinancialReportValidator validator;
    private final ModelFailureClassifier classifier;
    private final GeneratorFactory generatorFactory;
    private final FinancialQualityScorer scorer = new FinancialQualityScorer();
    private final FinancialTrendCalculator trendCalculator = new FinancialTrendCalculator();
    private final FinancialDeterministicComposer composer = new FinancialDeterministicComposer();

    public FinancialReportService(ResearchGateway gateway, StockAgentTools tools,
            NamedChatClientRegistry registry,
            Cache<SecurityId, DataSection<FinancialStatementHistory>> historyCache,
            FinancialReportValidator validator, ModelFailureClassifier classifier) {
        this(gateway, tools, registry, historyCache, validator, classifier,
                model -> new SpringAiFinancialNarrativeGenerator(model.client(), model.modelName()));
    }

    FinancialReportService(ResearchGateway gateway, StockAgentTools tools,
            NamedChatClientRegistry registry,
            Cache<SecurityId, DataSection<FinancialStatementHistory>> historyCache,
            FinancialReportValidator validator, ModelFailureClassifier classifier,
            GeneratorFactory generatorFactory) {
        this.gateway = Objects.requireNonNull(gateway, "gateway is required");
        this.tools = Objects.requireNonNull(tools, "tools is required");
        this.registry = registry == null ? new NamedChatClientRegistry(null, null) : registry;
        this.historyCache = Objects.requireNonNull(historyCache, "historyCache is required");
        this.validator = Objects.requireNonNull(validator, "validator is required");
        this.classifier = Objects.requireNonNull(classifier, "classifier is required");
        this.generatorFactory = Objects.requireNonNull(generatorFactory, "generatorFactory is required");
    }

    public FinancialReportAnalysis generate(String code) {
        SecurityId security = SecurityId.parse(code);
        DataSection<FinancialStatementHistory> section =
                historyCache.get(security, gateway::financialHistory);
        if (section == null || section.status() == SectionStatus.UNAVAILABLE
                || section.payload().isEmpty()) {
            String reason = section == null || section.issues() == null
                    ? "Sina 财报数据不可用" : String.join(";", section.issues());
            throw new FinancialDataUnavailableException(reason);
        }
        FinancialStatementHistory history = section.payload().orElseThrow();
        boolean financialIndustry = detectFinancialIndustry(code);
        FinancialQualityScore score = scorer.score(history, financialIndustry);
        FinancialTrendResult trends = trendCalculator.calculate(history);
        int pass = (int) score.signals().stream()
                .filter(item -> item.status() == FinancialQualityScore.SignalStatus.PASS).count();
        int fail = (int) score.signals().stream()
                .filter(item -> item.status() == FinancialQualityScore.SignalStatus.FAIL).count();
        int unverified = score.signals().size() - pass - fail;
        FinancialEvidencePackage pack = new FinancialEvidencePackage(code, history, score, trends,
                financialIndustry, pass, fail, unverified, score.signals().size());

        String traceId = "financial-" + UUID.randomUUID();
        long started = System.nanoTime();
        Optional<NamedChatClientRegistry.NamedModel> model = registry.forRole(ROLE);
        if (model.isEmpty()) {
            return compose(GenerationMode.DETERMINISTIC_FALLBACK, pack, null, null);
        }
        try {
            FinancialReportGenerator generator = generatorFactory.create(model.orElseThrow());
            FinancialNarrativeDraft draft = generator.generate(pack);
            FinancialReportValidator.Validation validation = validator.validate(draft, pack);
            if (validation.blocking()) {
                draft = generator.repair(pack, draft, validation.issues());
                validation = validator.validate(draft, pack);
            }
            if (validation.blocking()) {
                ModelDiagnostic diagnostic = classifier.validation(validation.issues(),
                        generator.modelName(), elapsedMillis(started), traceId);
                return compose(GenerationMode.DETERMINISTIC_FALLBACK, pack, null, diagnostic);
            }
            return compose(GenerationMode.MODEL_ASSISTED, pack,
                    new FinancialNarrative(draft.tierInterpretation(), draft.signalCommentary(),
                            draft.trendCommentary(), draft.riskNotes()), null);
        } catch (Exception failure) {
            ModelDiagnostic diagnostic = classifier.classify(failure,
                    model.map(NamedChatClientRegistry.NamedModel::modelName)
                            .orElse("configured-financial-model"),
                    elapsedMillis(started), traceId);
            return compose(GenerationMode.DETERMINISTIC_FALLBACK, pack, null, diagnostic);
        }
    }

    private boolean detectFinancialIndustry(String code) {
        try {
            StockResearchSnapshot snapshot = tools.getResearchSnapshot(code);
            if (snapshot == null || snapshot.sectors() == null) {
                return false;
            }
            Object payload = snapshot.sectors().payload().orElse(null);
            if (!(payload instanceof List<?> sectors)) {
                return false;
            }
            return sectors.stream().map(Object::toString).anyMatch(name ->
                    List.of("银行", "保险", "证券", "多元金融", "信托")
                            .stream().anyMatch(name::contains));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private FinancialReportAnalysis compose(GenerationMode mode, FinancialEvidencePackage pack,
            FinancialNarrative narrative, ModelDiagnostic diagnostic) {
        FinancialNarrative text = narrative != null ? narrative : composer.compose(pack);
        String range = pack.history().periodCount() == 0 ? "--"
                : pack.history().periods().get(0).reportPeriod() + " - "
                + pack.history().periods().get(pack.history().periodCount() - 1).reportPeriod();
        return new FinancialReportAnalysis(
                pack.securityCode(), range, pack.history().periodCount(),
                pack.qualityScore(), pack.trends(), text, mode, diagnostic,
                pack.financialIndustry(), Instant.now().toString(),
                FinancialQualityScorer.RULE_VERSION,
                mode == GenerationMode.MODEL_ASSISTED
                        ? SpringAiFinancialNarrativeGenerator.PROMPT_VERSION : "deterministic",
                FinancialReportAnalysis.REQUIRED_DISCLAIMER);
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
```

- [ ] **Step 5: 配置装配**

`application.yml` roles 追加一行:

```yaml
    roles:
      institutional-report: primary
      overall-report: deepseek
      financial-report: deepseek
```

`CacheConfiguration.java` 追加 bean + import(`FinancialStatementHistory`):

```java
    @Bean("financialHistoryCache")
    Cache<SecurityId, DataSection<FinancialStatementHistory>> financialHistoryCache() {
        return Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofHours(6)).build();
    }
```

`AgentConfiguration.java` 追加 bean(方法签名与 import):

```java
    @Bean
    FinancialReportService financialReportService(
            NamedChatClientRegistry registry,
            StockAgentTools tools,
            ResearchGateway gateway,
            @Qualifier("financialHistoryCache")
            Cache<SecurityId, DataSection<FinancialStatementHistory>> historyCache) {
        return new FinancialReportService(gateway, tools, registry, historyCache,
                new FinancialReportValidator(), new ModelFailureClassifier());
    }
```

(imports:`org.springframework.beans.factory.annotation.Qualifier`、`com.github.benmanes.caffeine.cache.Cache`、`com.astock.agent.marketdata.model.{DataSection,FinancialStatementHistory,SecurityId}`、`com.astock.agent.agent.financial.{FinancialReportService,FinancialReportValidator}`、`com.astock.agent.analysis.ResearchGateway`)

- [ ] **Step 6: 运行测试通过** — `'-Dtest=FinancialReportServiceTest' test`,预期 PASS。随后跑一次全量离线测试确认装配:`.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' test`(外部测试默认排除)。

- [ ] **Step 7: 提交(经用户确认后)** — `git commit -m "feat: add DeepSeek financial narrative pipeline and service"`

---

### Task 6: REST 端点 + 异常处理

**Files:**
- Modify: `src/main/java/com/astock/agent/web/AgentController.java`(新端点 + 构造器)
- Modify: `src/main/java/com/astock/agent/web/ApiExceptionHandler.java`(新 handler)
- Modify: `src/test/java/com/astock/agent/web/AgentControllerTest.java`(新测试)

**Interfaces:**
- Produces: `POST /api/agent/financial-report` body `{"code": "600519"}` → `FinancialReportAnalysis`;非法代码 `INVALID_SECURITY_CODE`;财报不可用 `FINANCIAL_DATA_UNAVAILABLE`(503)。

- [ ] **Step 1: 写失败测试**

在 `AgentControllerTest` 追加(先读该文件,沿用其既有构造风格与 mock 方式):

```java
    @Test
    void financialReportReturnsAnalysis() {
        FinancialReportAnalysis analysis = new FinancialReportAnalysis(
                "600519", "2023-06-30 - 2026-06-30", 12,
                new FinancialQualityScore(6, "良", 9, List.of(), true),
                new FinancialTrendResult(List.of(), 12),
                new FinancialNarrative("F-Score 为 6 分，档位 良。", "信号正常。", "趋势正常。", "无风险。"),
                GenerationMode.DETERMINISTIC_FALLBACK, null, false,
                "2026-08-31T00:00:00Z", "financial-fscore-v1", "deterministic",
                FinancialReportAnalysis.REQUIRED_DISCLAIMER);

        AgentController controller = new AgentController(statusService, agent,
                overallReports, quantReports, financialReports(analysis));

        FinancialReportAnalysis response = controller.financialReport(new AnalyzeRequest("600519"));

        assertThat(response.securityCode()).isEqualTo("600519");
        assertThat(response.qualityScore().total()).isEqualTo(6);
    }

    @Test
    void financialReportRejectsInvalidCode() {
        AgentController controller = new AgentController(statusService, agent,
                overallReports, quantReports, financialReports(null));

        assertThatThrownBy(() -> controller.financialReport(new AnalyzeRequest("abc")))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

(helper `financialReports(...)` 用 Mockito mock `FinancialReportService` 并 stub `generate("600519")`)

- [ ] **Step 2: 运行确认失败** — `'-Dtest=AgentControllerTest' test`,预期编译失败。

- [ ] **Step 3: 实现端点与异常处理**

`AgentController`:

```java
    private final FinancialReportService financialReports;

    // 既有构造器链尾追加 financialReports = null:
    public AgentController(AgentStatusService statusService, StockAnalysisAgent agent) {
        this(statusService, agent, null, null, null);
    }

    public AgentController(AgentStatusService statusService, StockAnalysisAgent agent,
            OverallReportService overallReports) {
        this(statusService, agent, overallReports, null, null);
    }

    @Autowired
    public AgentController(AgentStatusService statusService, StockAnalysisAgent agent,
            OverallReportService overallReports, QuantResearchReportService quantReports) {
        this(statusService, agent, overallReports, quantReports, null);
    }

    @Autowired
    public AgentController(AgentStatusService statusService, StockAnalysisAgent agent,
            OverallReportService overallReports, QuantResearchReportService quantReports,
            FinancialReportService financialReports) {
        this.statusService = statusService;
        this.agent = agent;
        this.overallReports = overallReports;
        this.quantReports = quantReports;
        this.financialReports = financialReports;
    }

    @PostMapping("/financial-report")
    public FinancialReportAnalysis financialReport(@RequestBody AnalyzeRequest request) {
        SecurityId.parse(request.code());
        if (financialReports == null) {
            throw new IllegalStateException("Financial report service is unavailable");
        }
        return financialReports.generate(request.code());
    }
```

`ApiExceptionHandler` 追加:

```java
    @ExceptionHandler(FinancialDataUnavailableException.class)
    ResponseEntity<ProblemDetail> financialDataUnavailable(FinancialDataUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "FINANCIAL_DATA_UNAVAILABLE",
                "财报数据不可用", exception.getMessage());
    }
```

- [ ] **Step 4: 运行测试通过** — `'-Dtest=AgentControllerTest,StockControllerTest' test`,预期 PASS(既有测试不回归)。

- [ ] **Step 5: 提交(经用户确认后)** — `git commit -m "feat: expose financial-report endpoint with problem details"`

---

### Task 7: 前端 Tab + 渲染 + Playwright 测试

**Files:**
- Modify: `src/main/resources/static/index.html`(第 8 个 Tab)
- Modify: `src/main/resources/static/js/api.js`(新增方法)
- Create: `src/main/resources/static/js/financial-view.js`
- Modify: `src/main/resources/static/js/app.js`(state、分支、绑定、重置)
- Modify: `src/main/resources/static/styles.css`(评分卡/信号清单样式)
- Modify: `src/main/resources/static/index.html:112`(app.js 版本号 query)
- Create: `tests/ui/financial-report.spec.js`

**Interfaces:**
- Consumes: `stockApi.financialReport(code)`;`FinancialReportAnalysis` JSON
- Produces: `renderFinancialViewShell()` → HTML;`renderFinancialReport(report)` → HTML;`activateFinancialChart(report, container)` → ECharts 控制器或 null

- [ ] **Step 1: index.html 加 Tab**

在「基本面」与「估值预期」之间插入:

```html
      <button class="view-tab" role="tab" aria-selected="false" data-view="financial"><i data-lucide="book-open"></i><span>财报分析</span></button>
```

- [ ] **Step 2: api.js 加方法**

```js
  financialReport(code) {
    return request("/api/agent/financial-report", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    });
  },
```

- [ ] **Step 3: financial-view.js**

```js
/*
 * 财报分析视图:评分卡、信号清单、趋势图与 DeepSeek 叙事。
 * 所有状态如实渲染:数据不足 / 确定性回退 / 模型诊断不渲染为 0 或空白。
 */

function escapeText(value) {
  const raw = String(value ?? "");
  return raw.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#039;");
}

function formatMoney(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "--";
  if (Math.abs(number) >= 1e8) return `${(number / 1e8).toLocaleString("zh-CN", { maximumFractionDigits: 1 })} 亿`;
  return number.toLocaleString("zh-CN", { maximumFractionDigits: 2 });
}

const SIGNAL_STATUS = {
  PASS: { label: "通过", tone: "ok" },
  FAIL: { label: "未通过", tone: "bad" },
  UNVERIFIED: { label: "无法评估", tone: "muted" },
};

const MODE_META = {
  MODEL_ASSISTED: { status: "HEALTHY", label: "DeepSeek 叙事已校验" },
  DETERMINISTIC_FALLBACK: { status: "DEGRADED", label: "确定性规则回退" },
};

const DIRECTION_LABELS = {
  RISING: "上升", FALLING: "下降", MIXED: "波动", INSUFFICIENT: "样本不足",
};

export function renderFinancialViewShell() {
  return `<section class="financial-view" aria-label="财报分析">
    <div class="financial-toolbar">
      <button id="run-financial-report" class="primary-command" type="button">
        <span>生成分析</span><i data-lucide="sparkles" aria-hidden="true"></i>
      </button>
      <div id="financial-request-status" aria-live="polite"></div>
    </div>
    <div id="financial-output"><p class="muted">基于新浪财报三表历史计算财务质量评分(F-Score)与多期趋势,由 DeepSeek 生成解读。</p></div>
  </section>`;
}

export function renderFinancialReport(report) {
  if (!report) return '<p class="muted">暂无财报分析结果。</p>';
  const score = report.qualityScore || {};
  const modeMeta = MODE_META[report.generationMode] || { status: "DEGRADED", label: "报告状态未知" };
  const signals = Array.isArray(score.signals) ? score.signals : [];
  const head = report.insufficientData || score.sufficientData === false
    ? `<div class="financial-score-card" data-tone="muted"><strong class="financial-score">--</strong><div><b>数据不足</b><span>报告期少于 4 期,无法计算 F-Score</span></div></div>`
    : `<div class="financial-score-card"><strong class="financial-score">${escapeText(score.total)}</strong><div><b>F-Score · ${escapeText(score.tier)}</b><span>0-2 弱 / 3-5 中 / 6-7 良 / 8-9 优 · 评估 ${escapeText(score.evaluatedSignals)} 个信号</span></div></div>`;
  const signalRows = signals.map((signal) => {
    const meta = SIGNAL_STATUS[signal.status] || SIGNAL_STATUS.UNVERIFIED;
    return `<li data-status="${escapeText(signal.status)}"><span class="signal-dot" data-tone="${escapeText(meta.tone)}"></span><strong>${escapeText(signal.number)} ${escapeText(signal.name)}</strong><span class="signal-label" data-tone="${escapeText(meta.tone)}">${escapeText(meta.label)}</span><small>${escapeText(signal.evidence || "")}</small></li>`;
  }).join("");
  const narrative = report.narrative || {};
  const diagnostic = report.diagnostic && Object.keys(report.diagnostic).length
    ? `<details class="model-diagnostic"><summary>模型诊断 · ${escapeText(report.diagnostic.errorCode || "MODEL_FAILURE")}</summary><dl><dt>阶段</dt><dd>${escapeText(report.diagnostic.failureStage || "--")}</dd><dt>原因</dt><dd>${escapeText(report.diagnostic.message || "--")}</dd><dt>模型</dt><dd>${escapeText(report.diagnostic.modelName || "--")}</dd><dt>追踪 ID</dt><dd>${escapeText(report.diagnostic.traceId || "--")}</dd></dl></details>`
    : "";
  const industryNote = report.financialIndustry
    ? '<p class="muted financial-note">金融行业:毛利率与资产周转率信号不适用传统口径。</p>' : "";
  return `<article class="financial-report">
    <header class="financial-report-header">
      <div><span class="source-status" data-status="${escapeText(modeMeta.status)}"><span></span>${escapeText(modeMeta.label)}</span>
        <h3>${escapeText(report.securityCode || "")} 财报分析</h3></div>
      <dl><dt>报告期</dt><dd>${escapeText(report.reportPeriodRange || "--")}</dd><dt>期数</dt><dd>${escapeText(report.periodCount == null ? "--" : `${report.periodCount} 期`)}</dd><dt>生成时间</dt><dd>${escapeText(report.generatedAt || "--")}</dd><dt>规则版本</dt><dd>${escapeText(report.ruleVersion || "--")}</dd></dl>
    </header>
    <div class="financial-grid">
      ${head}
      <div id="financial-trend-chart" class="financial-chart" aria-label="多期财务趋势图"></div>
    </div>
    <section class="financial-signals"><h4>财务质量信号</h4>${signals.length ? `<ul>${signalRows}</ul>` : '<p class="muted">数据不足,暂无信号明细。</p>'}</section>
    ${industryNote}
    <section class="financial-narrative"><h4>解读</h4>
      <p><strong>评分档位</strong> ${escapeText(narrative.tierInterpretation || "UNAVAILABLE：暂无解读。")}</p>
      <p><strong>信号归因</strong> ${escapeText(narrative.signalCommentary || "UNAVAILABLE：暂无归因。")}</p>
      <p><strong>趋势</strong> ${escapeText(narrative.trendCommentary || "UNAVAILABLE：暂无趋势解读。")}</p>
      <p><strong>风险与限制</strong> ${escapeText(narrative.riskNotes || "UNAVAILABLE：暂无风险说明。")}</p>
    </section>
    ${diagnostic}
    <small class="report-disclaimer">${escapeText(report.disclaimer || "仅供学习研究，不构成投资建议")}</small>
  </article>`;
}

export function activateFinancialChart(report, container) {
  if (!container || !report?.trends?.series?.length || !window.echarts) return null;
  const seriesList = report.trends.series;
  const periodKeys = [...new Set(seriesList.flatMap((series) => (series.points || []).map((point) => point.period)))].sort();
  const valueOf = (series, period) => {
    const point = (series.points || []).find((item) => item.period === period);
    return point && point.value != null ? Number(point.value) : null;
  };
  const chart = window.echarts.init(container);
  const bars = seriesList.filter((series) => series.unit === "元").slice(0, 2);
  const lines = seriesList.filter((series) => series.unit === "%").slice(0, 3);
  const formatYuan = (value) => (value == null ? "--" : value >= 1e8 ? `${(value / 1e8).toFixed(1)} 亿` : `${(value / 1e4).toFixed(1)} 万`);
  chart.setOption({
    tooltip: { trigger: "axis" },
    legend: { top: 0 },
    grid: { left: 60, right: 60, top: 32, bottom: 28 },
    xAxis: { type: "category", data: periodKeys.map((period) => period.slice(0, 7)), axisLabel: { color: "#807d72" } },
    yAxis: [
      { type: "value", name: "金额(累计)", axisLabel: { color: "#807d72", formatter: formatYuan }, splitLine: { lineStyle: { color: "#e6e5e0" } } },
      { type: "value", name: "比率 %", axisLabel: { color: "#807d72" }, splitLine: { show: false } },
    ],
    series: [
      ...bars.map((series, index) => ({
        name: series.name, type: "bar", yAxisIndex: 0,
        itemStyle: { color: index === 0 ? "#7158d9" : "#cfcdc4" },
        data: periodKeys.map((period) => valueOf(series, period)),
      })),
      ...lines.map((series, index) => ({
        name: series.name, type: "line", yAxisIndex: 1, smooth: true,
        lineStyle: { color: index === 0 ? "#d83b53" : index === 1 ? "#1f8a65" : "#807d72" },
        itemStyle: { color: index === 0 ? "#d83b53" : index === 1 ? "#1f8a65" : "#807d72" },
        data: periodKeys.map((period) => valueOf(series, period)),
      })),
    ],
  });
  return { dispose: () => chart.dispose() };
}
```

- [ ] **Step 4: app.js 接线**

imports 追加:

```js
import { activateFinancialChart, renderFinancialReport, renderFinancialViewShell } from "./financial-view.js";
```

state 追加:

```js
  financialReportPhase: "idle",
  financialReportResult: null,
  financialReportRequestId: 0,
```

`renderCurrentView()` 顶部 dispose 追加(在 technicalController dispose 旁):

```js
  state.financialController?.dispose();
  state.financialController = null;
```

并在 `if (state.currentView === "technical") {...}` 前插入:

```js
  if (state.currentView === "financial") {
    content.innerHTML = renderFinancialViewShell();
    bindFinancialAction();
    if (state.financialReportResult) {
      $("#financial-output").innerHTML = renderFinancialReport(state.financialReportResult);
      state.financialController = activateFinancialChart(state.financialReportResult, $("#financial-trend-chart"));
    }
    refreshIcons();
    return;
  }
```

`loadStock` 重置区追加(与 institutionalReport 重置并列):

```js
  state.financialReportResult = null;
  state.financialReportRequestId += 1;
```

新增绑定函数(放在 `bindAgentAction` 之后):

```js
function bindFinancialAction() {
  const button = $("#run-financial-report");
  if (!button) return;
  button.addEventListener("click", async () => {
    const reportCode = state.currentCode;
    const generation = state.loadGeneration;
    const requestId = ++state.financialReportRequestId;
    const isCurrent = () => state.financialReportRequestId === requestId
      && state.currentCode === reportCode && state.loadGeneration === generation;
    state.financialReportPhase = "loading";
    button.disabled = true;
    const status = $("#financial-request-status");
    if (status) status.innerHTML = '<span class="source-status" data-status="DEGRADED"><span></span>正在生成财报分析</span>';
    const output = $("#financial-output");
    try {
      const report = await stockApi.financialReport(reportCode);
      if (!isCurrent()) return;
      state.financialReportResult = report;
      if (output) output.innerHTML = renderFinancialReport(report);
      state.financialController = activateFinancialChart(report, $("#financial-trend-chart"));
      refreshIcons();
    } catch (error) {
      if (!isCurrent()) return;
      if (output) output.innerHTML = `<span class="source-status" data-status="UNAVAILABLE"><span></span>财报分析不可用</span><p>${escapeText(error.message || "请检查数据源配置")}</p>`;
    } finally {
      if (isCurrent()) {
        state.financialReportPhase = "idle";
        button.disabled = false;
        if (status) status.replaceChildren();
      }
    }
  });
}
```

- [ ] **Step 5: styles.css 追加**(末尾,沿用设计令牌)

```css
/* 财报分析 */
.financial-view { display: flex; flex-direction: column; gap: 16px; }
.financial-toolbar { display: flex; align-items: center; gap: 12px; }
.financial-report-header { display: flex; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.financial-report-header dl { display: grid; grid-template-columns: auto auto; gap: 2px 12px; font-size: 13px; color: var(--body); margin: 0; }
.financial-report-header dt { color: var(--muted); }
.financial-grid { display: grid; grid-template-columns: 280px 1fr; gap: 16px; align-items: stretch; }
.financial-score-card { display: flex; align-items: center; gap: 16px; background: var(--soft-canvas); border: 1px solid var(--hairline); border-radius: 10px; padding: 16px; }
.financial-score { font-size: 44px; line-height: 1; color: var(--purple); font-variant-numeric: tabular-nums; }
.financial-score-card b { display: block; font-size: 15px; color: var(--ink); }
.financial-score-card span { color: var(--muted); font-size: 12px; }
.financial-chart { width: 100%; height: 280px; }
.financial-signals ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.financial-signals li { display: flex; align-items: baseline; gap: 10px; border-bottom: 1px solid var(--hairline); padding: 6px 0; font-size: 13px; }
.financial-signals li small { color: var(--muted); margin-left: auto; text-align: right; }
.signal-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.signal-dot[data-tone="ok"] { background: var(--down-green); }
.signal-dot[data-tone="bad"] { background: var(--up-red); }
.signal-dot[data-tone="muted"] { background: var(--muted); }
.signal-label[data-tone="ok"] { color: var(--down-green); }
.signal-label[data-tone="bad"] { color: var(--up-red); }
.signal-label[data-tone="muted"] { color: var(--muted); }
.financial-narrative p { font-size: 14px; line-height: 1.7; color: var(--body); }
.financial-note { font-size: 12px; }
@media (max-width: 900px) { .financial-grid { grid-template-columns: 1fr; } }
```

> **注意:** 若 `--purple/--down-green/--up-red/--soft-canvas` 等变量名与 styles.css 现有定义不符,以文件中实际变量为准(执行时先 grep `--purple` 确认);AGENTS.md 令牌值:purple `#7158d9`、down-green `#1f8a65`、up-red `#d83b53`、soft-canvas `#fafaf7`、hairline `#e6e5e0`、muted `#807d72`、body `#5a5852`、ink `#26251e`。

- [ ] **Step 6: Playwright 测试**

先读 `tests/ui/dashboard.spec.js` 与 `playwright.config.js` 的 mock 与加载流程,再写 `tests/ui/financial-report.spec.js`:

```js
const { test, expect } = require("@playwright/test");
const fs = require("fs");
const path = require("path");

const FINANCIAL_REPORT = JSON.parse(fs.readFileSync(path.join(__dirname, "fixtures/financial-report.json"), "utf8"));

async function openFinancialTab(page) {
  await page.route("**/api/stocks/search*", (route) => route.fulfill({ contentType: "application/json", body: "[]" }));
  await page.route("**/api/agent/status", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ status: "READY", details: "", institutionalReport: "READY", overallReport: "READY" }),
  }));
  await page.route("**/api/stocks/600519/snapshot", (route) => route.fulfill({
    contentType: "application/json",
    body: fs.readFileSync(path.join(__dirname, "fixtures/partial-snapshot.json"), "utf8"),
  }));
  await page.goto("/");
  await page.locator("input#stock-query").fill("600519");
  await page.keyboard.press("Enter");
  await expect(page.locator("#security-code")).toHaveText("600519");
  await page.locator('.view-tab[data-view="financial"]').click();
}

test.describe("财报分析 Tab", () => {
  test.beforeEach(async ({ page }) => {
    await page.route("**/api/agent/financial-report", (route) => route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(FINANCIAL_REPORT),
    }));
  });

  for (const viewport of [{ width: 1440, height: 1000 }, { width: 1024, height: 768 }, { width: 768, height: 1024 }, { width: 390, height: 844 }]) {
    test(`renders score, signals and chart at ${viewport.width}x${viewport.height}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await openFinancialTab(page);
      await page.locator("#run-financial-report").click();
      await expect(page.locator(".financial-score")).toHaveText("6");
      await expect(page.locator(".financial-signals li")).toHaveCount(9);
      await expect(page.locator("#financial-trend-chart canvas")).toBeVisible();
      await expect(page.locator(".financial-narrative")).toContainText("良");
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow).toBeLessThanOrEqual(0);
    });
  }

  test("shows deterministic fallback badge", async ({ page }) => {
    await openFinancialTab(page);
    await page.locator("#run-financial-report").click();
    await expect(page.locator(".financial-report-header .source-status")).toContainText("回退");
  });

  test("shows unavailable state when data source fails", async ({ page }) => {
    await page.route("**/api/agent/financial-report", (route) => route.fulfill({
      status: 503,
      contentType: "application/problem+json",
      body: JSON.stringify({ type: "about:blank", title: "财报数据不可用", status: 503, detail: "Sina statements failed", code: "FINANCIAL_DATA_UNAVAILABLE" }),
    }));
    await openFinancialTab(page);
    await page.locator("#run-financial-report").click();
    await expect(page.locator("#financial-output")).toContainText("财报分析不可用");
  });
});
```

创建 `tests/ui/fixtures/financial-report.json`:一个与 `FinancialReportAnalysis` 契约一致的 JSON(generationMode 用 `DETERMINISTIC_FALLBACK` 以覆盖回退徽标测试;qualityScore.total=6、tier=良、9 信号明细(6 PASS/3 FAIL/0 UNVERIFIED)、trends 8 系列各 12 点、narrative 四段含"良")。数字与叙事保持一致,避免与后端校验无关(前端不校验)。

- [ ] **Step 7: 运行 UI 测试**

```powershell
npm.cmd run test:ui
```

预期:全部 PASS(含既有 dashboard 用例)。若启动脚本需要后端在跑:按 `start.ps1` 起服务(需要 local 配置时仅 UI 测试用 mock 即可,playwright webServer 配置见 playwright.config.js)。

- [ ] **Step 8: 提交(经用户确认后)** — `git commit -m "feat: add financial analysis tab with chart and narrative"`

---

### Task 8: 实盘验证 + 脚本 + 文档 + 全量验收

**Files:**
- Create: `src/test/java/com/astock/agent/external/FinancialReportLiveIT.java`
- Modify: `scripts/verify-data.cmd`(加财报分析验证段落;先读现有内容)
- Modify: `README.md`(API 表加一行;先读现有内容)

- [ ] **Step 1: 外部实盘 IT**(镜像 `LiveDataIT` 的 `@Tag("external")` 风格;先读该文件)

```java
package com.astock.agent.external;

import com.astock.agent.agent.financial.FinancialReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("external")
@SpringBootTest
class FinancialReportLiveIT {

    @Autowired FinancialReportService service;

    @Test
    void generatesLiveFinancialReport() throws Exception {
        var analysis = service.generate("600519");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path output = Path.of("target/data-verification/financial-report-600519.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(analysis));
    }
}
```

- [ ] **Step 2: 实盘运行(需 local 配置)**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Pexternal' '-Dtest=FinancialReportLiveIT' test
```

预期:输出 `target/data-verification/financial-report-600519.json`,人工检查评分/趋势/叙事合理性(茅台应得分较高、档位良/优、期数 ≥ 8)。

- [ ] **Step 3: verify-data 脚本与 README**

读 `scripts/verify-data.cmd` 后,在其股票验证段落旁追加财报分析 curl:

```cmd
curl -s -X POST http://127.0.0.1:10001/api/agent/financial-report -H "Content-Type: application/json" -d "{\"code\":\"600519\"}" > target\data-verification\financial-report.json
```

README 的 API 清单(若有表)加:

```markdown
| `POST /api/agent/financial-report` | 财报分析(F-Score + 趋势 + DeepSeek 叙事) | `{code}` |
```

- [ ] **Step 4: 全量验收**

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
npm.cmd run test:ui
git diff --check
```

预期:Maven 全绿(离线默认)、Playwright 全绿、diff 无空白错误。再人工启动 `start.ps1`,浏览器走一遍:搜索 600519 → 财报分析 Tab → 生成分析 → 核对评分卡/信号/图表/叙事/来源。

- [ ] **Step 5: 提交(经用户确认后)** — `git commit -m "test: add live financial report verification"`

---

## Self-Review 结果

1. **Spec 覆盖**:数据层(§4)→ Task 1;评分(§5)→ Task 2;趋势(§6)→ Task 3;叙事与校验(§7)→ Task 4/5;API(§8)→ Task 6;前端(§9)→ Task 7;测试验收(§10)→ 各任务测试 + Task 8。非目标(不做 DuPont/Beneish/预期差/原文解析/批量)在计划中无对应工作 ✓
2. **占位符扫描**:无 TBD;关键类均有完整代码或精确锚点;两处执行时确认项(DataSection.healthy null provenance、CSS 变量名)已显式标注确认方法 ✓
3. **类型一致性**:`FinancialQualityScorer.score(history, financialIndustry)`、`FinancialTrendCalculator.calculate(history)`、`FinancialReportService.generate(code)`、`SignalResult/SignalStatus/Validation.blocking()`、`REQUIRED_DISCLAIMER` 在各任务间命名一致 ✓
4. **已知取舍**:趋势引擎 ROE_TTM 每期分母用当期归母股东权益(时点),已在代码注释与 spec 中说明;金融行业判定基于板块名称启发式,sectors 不可用时视为非金融(保守不误标) ✓
