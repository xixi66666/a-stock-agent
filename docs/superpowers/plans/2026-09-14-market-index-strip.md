# 大盘指数条与两行行情卡片实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 工作台行情卡片新增一行大盘指数条（上证指数、深证成指、创业板指的点位与涨跌幅），原有内容下移第二行；数据走同花顺指数快照主源、腾讯基准日线局部回退。

**Architecture:** `MarketIndexService` 固定三指数调用新 `IndexQuoteGateway`；`HithinkIndexQuoteGateway` 用 `HithinkFinanceClient` 批量快照，单指数失败时由 `BenchmarkDataGateway.bars` 最近两根日线局部回退；`GET /api/market/indices` 返回带身份的分区列表；前端首行 `.market-strip` 跨列渲染。

**Tech Stack:** Java 21, Spring Boot 3.5.x, Jackson, JUnit 5 + AssertJ + MockMvc, 原生 JS 模块, Playwright。

**设计文档:** `docs/superpowers/specs/2026-09-14-market-index-strip-design.md`

## Global Constraints

- 所有测试默认离线；外部 Provider 测试必须标 `@Tag("external")`（pom 默认排除，`-Pexternal` 才跑）。
- 缺失数据必须 `UNAVAILABLE`/`STALE`/`UNVERIFIED` + 原因，禁止补造数值；Provenance 全程保留。
- 真实 API Key 只能进 `config/application-local.yml`（Git 忽略）；不得出现在日志、测试夹具或提交中。
- A 股语义红涨绿跌；颜色不能是唯一信息载体；设计令牌见 AGENTS.md。
- Maven 命令统一先设置 JDK：
  `$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path; $env:Path = "$env:JAVA_HOME\bin;$env:Path"; .\mvnw.cmd '-Dmaven.repo.local=.m2/repository' <goal>`
- 前端不计算点位或涨跌幅；确定性计算只在 Java。
- 不同花顺代码复用 `SecurityId`；指数身份用 `BenchmarkId`。
- 提交需用户确认后执行。

---

### Task 1: BenchmarkId 指数扩展与同花顺代码映射

**Files:**
- Modify: `src/main/java/com/astock/agent/marketdata/model/BenchmarkId.java`
- Modify: `src/main/java/com/astock/agent/marketdata/provider/hithink/HithinkFinanceClient.java:156-160`
- Create: `src/test/java/com/astock/agent/marketdata/model/BenchmarkIdTest.java`

**Interfaces:**
- Produces: `BenchmarkId.SHANGHAI_COMPOSITE`、`SHENZHEN_COMPONENT`、`CHI_NEXT`；`BenchmarkId.hithinkCode()` → `"000001.SH"` / `"399001.SZ"` / `"399006.SZ"`

- [x] **Step 1: 写失败测试**

```java
package com.astock.agent.marketdata.model;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class BenchmarkIdTest {
    @Test void mapsTencentCodesToHithinkCodes() {
        assertThat(BenchmarkId.CSI_300.hithinkCode()).isEqualTo("000300.SH");
        assertThat(BenchmarkId.SHANGHAI_COMPOSITE.hithinkCode()).isEqualTo("000001.SH");
        assertThat(BenchmarkId.SHENZHEN_COMPONENT.hithinkCode()).isEqualTo("399001.SZ");
        assertThat(BenchmarkId.CHI_NEXT.hithinkCode()).isEqualTo("399006.SZ");
    }

    @Test void keepsDisplayNamesForMarketStrip() {
        assertThat(BenchmarkId.SHANGHAI_COMPOSITE.displayName()).isEqualTo("上证指数");
        assertThat(BenchmarkId.SHENZHEN_COMPONENT.displayName()).isEqualTo("深证成指");
        assertThat(BenchmarkId.CHI_NEXT.displayName()).isEqualTo("创业板指");
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=BenchmarkIdTest' test`
Expected: 编译失败，`hithinkCode()` / 新枚举不存在

- [x] **Step 3: 实现**

`BenchmarkId` 增加常量与方法：

```java
public enum BenchmarkId {
    CSI_300("sh000300", "沪深300"),
    CSI_500("sh000905", "中证500"),
    CSI_1000("sh000852", "中证1000"),
    SHANGHAI_COMPOSITE("sh000001", "上证指数"),
    SHENZHEN_COMPONENT("sz399001", "深证成指"),
    CHI_NEXT("sz399006", "创业板指");

    // 构造器、tencentCode()、displayName() 保持不变

    public String hithinkCode() {
        return tencentCode.substring(2) + "." + tencentCode.substring(0, 2).toUpperCase(java.util.Locale.ROOT);
    }
}
```

`HithinkFinanceClient.fetchBenchmarkBars` 改为：

```java
public DataSection<List<DailyBar>> fetchBenchmarkBars(BenchmarkId benchmark) {
    if (benchmark == null) return DataSection.unavailable("基准指数未指定");
    return history(benchmark.hithinkCode(), true);
}
```

- [x] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=BenchmarkIdTest,HithinkFinanceClientTest' test`
Expected: PASS

---

### Task 2: IndexQuote 模型与同花顺指数快照解析

**Files:**
- Create: `src/main/java/com/astock/agent/marketdata/model/IndexQuote.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/IndexQuoteSection.java`
- Modify: `src/main/java/com/astock/agent/marketdata/provider/hithink/HithinkFinanceClient.java`
- Modify: `src/test/java/com/astock/agent/marketdata/provider/HithinkFinanceClientTest.java`

**Interfaces:**
- Produces:
  - `record IndexQuote(BenchmarkId benchmark, String displayName, BigDecimal lastPoint, BigDecimal previousClose, BigDecimal changeAmount, BigDecimal changePercent, Instant quotedAt)`
  - `record IndexQuoteSection(BenchmarkId benchmark, String displayName, DataSection<IndexQuote> quote)`
  - `HithinkFinanceClient.fetchIndexQuotes(List<BenchmarkId>)` → `List<DataSection<IndexQuote>>`（顺序与入参一致）

- [x] **Step 1: 写失败测试**

在 `HithinkFinanceClientTest` 追加（沿用类内 `body`、`client`、`clock`）：

```java
@Test void indexSnapshotKeepsPerIndexStatusAndRejectsUnknownIdentity() {
    body.set("""
            {"code":0,"data":{"timestamp":1789081200000,"item":[
            {"thscode":"000001.SH","name":"上证指数","last_price":3123.45,"prev_price":3113.2,
             "price_change":10.25,"price_change_ratio_pct":0.33},
            {"thscode":"399001.SZ","name":"深证成指","last_price":10123.45,"prev_price":10200,
             "price_change":-76.55,"price_change_ratio_pct":-0.75}]}}
            """);
    var sections = client.fetchIndexQuotes(List.of(BenchmarkId.SHANGHAI_COMPOSITE,
            BenchmarkId.SHENZHEN_COMPONENT, BenchmarkId.CHI_NEXT));
    assertThat(sections).hasSize(3);
    assertThat(sections.get(0).status()).isEqualTo(SectionStatus.UNVERIFIED);
    var shanghai = sections.get(0).payload().orElseThrow();
    assertThat(shanghai.benchmark()).isEqualTo(BenchmarkId.SHANGHAI_COMPOSITE);
    assertThat(shanghai.displayName()).isEqualTo("上证指数");
    assertThat(shanghai.lastPoint()).isEqualByComparingTo("3123.45");
    assertThat(shanghai.changePercent()).isEqualByComparingTo("0.33");
    assertThat(shanghai.quotedAt()).isEqualTo(Instant.parse("2026-09-10T23:00:00Z"));
    assertThat(sections.get(1).payload().orElseThrow().changePercent()).isEqualByComparingTo("-0.75");
    assertThat(sections.get(2).status()).isEqualTo(SectionStatus.UNAVAILABLE);
    assertThat(sections.get(2).issues()).anyMatch(s -> s.contains("缺少"));
    assertThat(sections.get(0).provenance().orElseThrow().sourceUrl().getQuery())
            .contains("thscodes=000001.SH,399001.SZ,399006.SZ");
    body.set(body.get().replace("\"399001.SZ\",\"name\":\"深证成指\"",
            "\"399999.SZ\",\"name\":\"未知\""));
    var rejected = client.fetchIndexQuotes(List.of(BenchmarkId.SHANGHAI_COMPOSITE));
    assertThat(rejected.getFirst().status()).isEqualTo(SectionStatus.UNAVAILABLE);
}

@Test void indexSnapshotWithoutKeyDoesNotRequest() {
    var unauthClient = new HithinkFinanceClient(null, clock, "", URI.create("http://127.0.0.1:1"));
    var sections = unauthClient.fetchIndexQuotes(List.of(BenchmarkId.SHANGHAI_COMPOSITE));
    assertThat(sections.getFirst().status()).isEqualTo(SectionStatus.UNAVAILABLE);
    assertThat(sections.getFirst().issues()).anyMatch(s -> s.contains("API Key"));
}
```

> 时间断言：`1789081200000` = 2026-09-10T23:00:00Z，位于测试类固定时钟（2026-09-12T00:00:00Z）的七天内，因此状态应为 `UNVERIFIED`。

- [x] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=HithinkFinanceClientTest' test`
Expected: 编译失败 / 方法不存在

- [x] **Step 3: 实现模型**

```java
package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.time.Instant;

public record IndexQuote(
        BenchmarkId benchmark,
        String displayName,
        BigDecimal lastPoint,
        BigDecimal previousClose,
        BigDecimal changeAmount,
        BigDecimal changePercent,
        Instant quotedAt) {
}
```

```java
package com.astock.agent.marketdata.model;

import java.util.Objects;

public record IndexQuoteSection(BenchmarkId benchmark, String displayName, DataSection<IndexQuote> quote) {
    public IndexQuoteSection {
        Objects.requireNonNull(benchmark, "benchmark");
        Objects.requireNonNull(quote, "quote");
        displayName = displayName == null ? benchmark.displayName() : displayName;
    }
}
```

- [x] **Step 4: 实现客户端方法**

在 `HithinkFinanceClient` 增加：

```java
/** Batch index snapshot; one index failing never invalidates healthy rows. */
public List<DataSection<IndexQuote>> fetchIndexQuotes(List<BenchmarkId> benchmarks) {
    if (benchmarks == null || benchmarks.isEmpty()) return List.of();
    List<BenchmarkId> requested = List.copyOf(benchmarks);
    if (apiKey.isBlank()) return unavailableIndices(requested, "未启用或未配置 API Key");
    String codes = requested.stream().map(BenchmarkId::hithinkCode)
            .collect(java.util.stream.Collectors.joining(","));
    URI uri = base.resolve("/api/a-share-index/prices/snapshot?thscodes=" + codes);
    try {
        JsonNode data = get(uri);
        Instant time = timestamp(data.path("timestamp"));
        Map<String, JsonNode> rows = new java.util.LinkedHashMap<>();
        for (JsonNode row : data.path("item")) {
            String thscode = row.path("thscode").asText("");
            boolean known = requested.stream().anyMatch(id -> id.hithinkCode().equals(thscode));
            if (!known) throw new ContractFailure("指数行情返回未请求的身份");
            if (rows.put(thscode, row) != null) throw new ContractFailure("指数行情重复身份");
        }
        var source = new Provenance(ProviderId.HITHINK.displayName(), uri, time, clock.instant(), false, null);
        var result = new ArrayList<DataSection<IndexQuote>>();
        for (BenchmarkId benchmark : requested) {
            JsonNode row = rows.get(benchmark.hithinkCode());
            if (row == null) {
                result.add(DataSection.unavailable("同花顺指数行情缺少 " + benchmark.displayName()));
                continue;
            }
            try {
                BigDecimal last = requiredPositive(row, "last_price");
                BigDecimal previous = requiredPositive(row, "prev_price");
                var quote = new IndexQuote(benchmark, benchmark.displayName(), last, previous,
                        number(row, "price_change"), number(row, "price_change_ratio_pct"), time);
                var issues = new ArrayList<String>();
                issues.add("同花顺指数行情源时间是数据就绪时间，不是成交时间");
                if (time != null && time.isBefore(clock.instant().minus(Duration.ofDays(7)))) {
                    result.add(DataSection.stale(quote, source, issues));
                } else {
                    result.add(DataSection.unverified(quote, source, issues));
                }
            } catch (Exception failure) {
                result.add(DataSection.unavailable("同花顺指数行情数值无效：" + safeFailure(failure)));
            }
        }
        return result;
    } catch (Exception failure) {
        return unavailableIndices(requested, "同花顺指数行情不可用：" + safeFailure(failure));
    }
}

private static List<DataSection<IndexQuote>> unavailableIndices(List<BenchmarkId> benchmarks, String issue) {
    return benchmarks.stream().map(ignored -> DataSection.<IndexQuote>unavailable(issue)).toList();
}
```

- [x] **Step 5: 运行确认通过**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=HithinkFinanceClientTest' test`
Expected: PASS

---

### Task 3: IndexQuoteGateway 主备网关与 MarketIndexService

**Files:**
- Create: `src/main/java/com/astock/agent/marketdata/provider/IndexQuoteGateway.java`
- Create: `src/main/java/com/astock/agent/analysis/HithinkIndexQuoteGateway.java`
- Create: `src/main/java/com/astock/agent/analysis/MarketIndexSnapshot.java`
- Create: `src/main/java/com/astock/agent/analysis/MarketIndexService.java`
- Modify: `src/main/java/com/astock/agent/config/MarketDataConfiguration.java`
- Create: `src/test/java/com/astock/agent/analysis/HithinkIndexQuoteGatewayTest.java`

**Interfaces:**
- Consumes: `HithinkFinanceClient.fetchIndexQuotes`、`BenchmarkDataGateway.bars`
- Produces:
  - `IndexQuoteGateway.quotes(List<BenchmarkId>)` → `List<IndexQuoteSection>`
  - `MarketIndexService.snapshot()` → `MarketIndexSnapshot(List<IndexQuoteSection> indices)`

- [x] **Step 1: 写失败测试**

```java
package com.astock.agent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.*;
import com.astock.agent.marketdata.provider.*;
import com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class HithinkIndexQuoteGatewayTest {
    private HttpServer server;
    private HithinkFinanceClient client;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger fallbackCalls = new AtomicInteger();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);
    private final List<BenchmarkId> three = List.of(BenchmarkId.SHANGHAI_COMPOSITE,
            BenchmarkId.SHENZHEN_COMPONENT, BenchmarkId.CHI_NEXT);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var http = new ProviderHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(2), 0,
                new ProviderThrottle(Duration.ZERO, Duration.ZERO),
                new ProviderHealthRegistry(Duration.ofSeconds(1), clock), clock);
        client = new HithinkFinanceClient(http, clock, "test-only-key",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void healthyPrimaryNeverCallsFallback() {
        body.set(snapshotBody("000001.SH", "399001.SZ", "399006.SZ"));
        var gateway = new HithinkIndexQuoteGateway(client, benchmark -> {
            fallbackCalls.incrementAndGet();
            return DataSection.unavailable("不应被调用");
        });
        var sections = gateway.quotes(three);
        assertThat(fallbackCalls).hasValue(0);
        assertThat(sections).hasSize(3);
        assertThat(sections.get(0).quote().status()).isEqualTo(SectionStatus.UNVERIFIED);
        assertThat(sections.get(0).benchmark()).isEqualTo(BenchmarkId.SHANGHAI_COMPOSITE);
        assertThat(sections.get(0).quote().provenance().orElseThrow().provider()).isEqualTo("HiThink Finance");
    }

    @Test void failedPrimaryFallsBackToLatestTwoBenchmarkBars() {
        body.set("{\"code\":1,\"message\":\"denied\"}");
        var gateway = new HithinkIndexQuoteGateway(client, benchmark -> DataSection.healthy(
                List.of(bar("2026-09-10", "3000"), bar("2026-09-11", "3030")), tencentSource()));

        var sections = gateway.quotes(List.of(BenchmarkId.SHANGHAI_COMPOSITE));
        var quote = sections.getFirst().quote().payload().orElseThrow();
        assertThat(sections.getFirst().quote().status()).isEqualTo(SectionStatus.UNVERIFIED);
        assertThat(quote.lastPoint()).isEqualByComparingTo("3030");
        assertThat(quote.previousClose()).isEqualByComparingTo("3000");
        assertThat(quote.changeAmount()).isEqualByComparingTo("30");
        assertThat(quote.changePercent()).isEqualByComparingTo("1.0000");
        assertThat(sections.getFirst().quote().issues())
                .anyMatch(s -> s.contains("收盘口径")).anyMatch(s -> s.contains("2026-09-11"));
        assertThat(sections.getFirst().quote().provenance().orElseThrow().fallbackProvider())
                .isEqualTo("HiThink Finance");
    }

    @Test void bothSourcesUnavailableStayUnavailableWithBothReasons() {
        body.set("{\"code\":1,\"message\":\"denied\"}");
        var gateway = new HithinkIndexQuoteGateway(client,
                benchmark -> DataSection.unavailable("腾讯日线不可用"));
        var section = gateway.quotes(List.of(BenchmarkId.SHANGHAI_COMPOSITE)).getFirst().quote();
        assertThat(section.status()).isEqualTo(SectionStatus.UNAVAILABLE);
        assertThat(section.issues()).anyMatch(s -> s.contains("腾讯日线不可用"))
                .anyMatch(s -> s.contains("同花顺"));
    }

    @Test void partialPrimaryFallsBackOnlyForTheMissingIndex() {
        body.set(snapshotBody("000001.SH", "399001.SZ"));
        var gateway = new HithinkIndexQuoteGateway(client, benchmark -> {
            fallbackCalls.incrementAndGet();
            return DataSection.healthy(List.of(bar("2026-09-10", "2000"), bar("2026-09-11", "1980")),
                    tencentSource());
        });
        var sections = gateway.quotes(three);
        assertThat(fallbackCalls).hasValue(1);
        assertThat(sections.get(0).quote().status()).isEqualTo(SectionStatus.UNVERIFIED);
        assertThat(sections.get(0).quote().provenance().orElseThrow().provider()).isEqualTo("HiThink Finance");
        assertThat(sections.get(2).benchmark()).isEqualTo(BenchmarkId.CHI_NEXT);
        assertThat(sections.get(2).quote().payload().orElseThrow().lastPoint()).isEqualByComparingTo("1980");
        assertThat(sections.get(2).quote().provenance().orElseThrow().fallbackProvider())
                .isEqualTo("HiThink Finance");
    }

    private static String snapshotBody(String... codes) {
        var rows = java.util.Arrays.stream(codes).map(code ->
                "{\"thscode\":\"" + code + "\",\"last_price\":3100,\"prev_price\":3080,"
                        + "\"price_change\":20,\"price_change_ratio_pct\":0.65}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"code\":0,\"data\":{\"timestamp\":1789081200000,\"item\":[" + rows + "]}}";
    }

    private static DailyBar bar(String date, String close) {
        return new DailyBar(LocalDate.parse(date), new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal("1000"), null);
    }

    private static Provenance tencentSource() {
        return new Provenance("Tencent", URI.create("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"),
                null, Instant.parse("2026-09-12T00:00:00Z"), false, null);
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=HithinkIndexQuoteGatewayTest' test`
Expected: 编译失败，网关与类型不存在

- [x] **Step 3: 实现**

`IndexQuoteGateway`：

```java
package com.astock.agent.marketdata.provider;

import com.astock.agent.marketdata.model.BenchmarkId;
import com.astock.agent.marketdata.model.IndexQuoteSection;
import java.util.List;

public interface IndexQuoteGateway {
    List<IndexQuoteSection> quotes(List<BenchmarkId> benchmarks);
}
```

`MarketIndexSnapshot`：

```java
package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.IndexQuoteSection;
import java.util.List;

public record MarketIndexSnapshot(List<IndexQuoteSection> indices) {
    public MarketIndexSnapshot {
        indices = indices == null ? List.of() : List.copyOf(indices);
    }
}
```

`MarketIndexService`：

```java
package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.BenchmarkId;
import com.astock.agent.marketdata.provider.IndexQuoteGateway;
import java.util.List;

public final class MarketIndexService {
    public static final List<BenchmarkId> MARKET_INDICES = List.of(
            BenchmarkId.SHANGHAI_COMPOSITE, BenchmarkId.SHENZHEN_COMPONENT, BenchmarkId.CHI_NEXT);

    private final IndexQuoteGateway gateway;

    public MarketIndexService(IndexQuoteGateway gateway) {
        this.gateway = gateway;
    }

    public MarketIndexSnapshot snapshot() {
        return new MarketIndexSnapshot(gateway.quotes(MARKET_INDICES));
    }
}
```

`HithinkIndexQuoteGateway`：

```java
package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.*;
import com.astock.agent.marketdata.provider.BenchmarkDataGateway;
import com.astock.agent.marketdata.provider.IndexQuoteGateway;
import com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** HiThink index snapshot first; Tencent benchmark daily bars fill only unavailable indices. */
public final class HithinkIndexQuoteGateway implements IndexQuoteGateway {
    private final HithinkFinanceClient hithink;
    private final BenchmarkDataGateway bars;

    public HithinkIndexQuoteGateway(HithinkFinanceClient hithink, BenchmarkDataGateway bars) {
        this.hithink = hithink;
        this.bars = bars;
    }

    @Override public List<IndexQuoteSection> quotes(List<BenchmarkId> benchmarks) {
        if (benchmarks == null || benchmarks.isEmpty()) return List.of();
        List<BenchmarkId> requested = List.copyOf(benchmarks);
        List<DataSection<IndexQuote>> primary = hithink.fetchIndexQuotes(requested);
        var result = new ArrayList<IndexQuoteSection>();
        for (int i = 0; i < requested.size(); i++) {
            BenchmarkId benchmark = requested.get(i);
            DataSection<IndexQuote> section = i < primary.size()
                    ? primary.get(i) : DataSection.unavailable("同花顺指数行情未返回该指数");
            if (section.payload().isPresent() && section.status() != SectionStatus.UNAVAILABLE) {
                result.add(new IndexQuoteSection(benchmark, benchmark.displayName(), section));
            } else {
                result.add(new IndexQuoteSection(benchmark, benchmark.displayName(), fallback(benchmark, section)));
            }
        }
        return List.copyOf(result);
    }

    private DataSection<IndexQuote> fallback(BenchmarkId benchmark, DataSection<IndexQuote> primary) {
        var issues = new ArrayList<>(primary.issues());
        DataSection<List<DailyBar>> history = bars.bars(benchmark);
        if (history.payload().isEmpty() || history.payload().orElseThrow().size() < 2
                || history.provenance().isEmpty()) {
            issues.add("腾讯基准日线回退不可用");
            issues.addAll(history.issues());
            return DataSection.unavailable(String.join("；", issues));
        }
        var series = history.payload().orElseThrow();
        var last = series.get(series.size() - 1);
        var previous = series.get(series.size() - 2);
        var change = last.close().subtract(previous.close());
        var percent = change.multiply(BigDecimal.valueOf(100))
                .divide(previous.close(), 4, RoundingMode.HALF_UP);
        var quote = new IndexQuote(benchmark, benchmark.displayName(), last.close(), previous.close(),
                change, percent, null);
        issues.add("腾讯基准日线回退：最新已完成交易日（" + last.date() + "）收盘口径，不是实时行情");
        issues.addAll(history.issues());
        var p = history.provenance().orElseThrow();
        var source = new Provenance(p.provider(), p.sourceUrl(), p.providerTimestamp(), p.fetchedAt(),
                p.cached(), "HiThink Finance");
        return DataSection.unverified(quote, source, issues);
    }
}
```

`MarketDataConfiguration` 增加 bean：

```java
@Bean IndexQuoteGateway indexQuoteGateway(HithinkFinanceClient hithink, BenchmarkDataGateway benchmarkDataGateway) {
    return new com.astock.agent.analysis.HithinkIndexQuoteGateway(hithink, benchmarkDataGateway);
}

@Bean com.astock.agent.analysis.MarketIndexService marketIndexService(IndexQuoteGateway gateway) {
    return new com.astock.agent.analysis.MarketIndexService(gateway);
}
```

- [x] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=HithinkIndexQuoteGatewayTest,BenchmarkIdTest' test`
Expected: PASS

---

### Task 4: MarketController 接口与测试

**Files:**
- Create: `src/main/java/com/astock/agent/web/MarketController.java`
- Create: `src/test/java/com/astock/agent/web/MarketControllerTest.java`

**Interfaces:**
- Consumes: `MarketIndexService.snapshot()`
- Produces: `GET /api/market/indices` → `{ "indices": [ { benchmark, displayName, quote: { status, payload, provenance, issues } } ] }`

- [x] **Step 1: 写失败测试**

```java
package com.astock.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.analysis.MarketIndexService;
import com.astock.agent.marketdata.model.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketControllerTest {
    private final MarketIndexService service = new MarketIndexService(benchmarks -> List.of(
            new IndexQuoteSection(BenchmarkId.SHANGHAI_COMPOSITE, "上证指数",
                    DataSection.unverified(new IndexQuote(BenchmarkId.SHANGHAI_COMPOSITE, "上证指数",
                                    new BigDecimal("3123.45"), new BigDecimal("3113.20"),
                                    new BigDecimal("10.25"), new BigDecimal("0.33"),
                                    Instant.parse("2026-09-14T01:36:13Z")),
                            new Provenance("HiThink Finance", URI.create("https://example.test/snapshot"),
                                    Instant.parse("2026-09-14T01:36:00Z"), Instant.parse("2026-09-14T01:36:10Z"),
                                    false, null),
                            List.of("同花顺指数行情源时间是数据就绪时间，不是成交时间"))),
            new IndexQuoteSection(BenchmarkId.CHI_NEXT, "创业板指",
                    DataSection.unavailable("同花顺指数行情不可用；腾讯基准日线回退不可用"))));

    @Test void indicesExposeIdentityStatusAndPayload() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MarketController(service)).build();
        mvc.perform(get("/api/market/indices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indices[0].benchmark").value("SHANGHAI_COMPOSITE"))
                .andExpect(jsonPath("$.indices[0].displayName").value("上证指数"))
                .andExpect(jsonPath("$.indices[0].quote.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.indices[0].quote.payload.lastPoint").value(3123.45))
                .andExpect(jsonPath("$.indices[0].quote.payload.changePercent").value(0.33))
                .andExpect(jsonPath("$.indices[1].benchmark").value("CHI_NEXT"))
                .andExpect(jsonPath("$.indices[1].quote.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.indices[1].quote.payload").doesNotExist())
                .andExpect(jsonPath("$.indices[1].quote.issues[0]").isNotEmpty());
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=MarketControllerTest' test`
Expected: 编译失败，`MarketController` 不存在

- [x] **Step 3: 实现**

```java
package com.astock.agent.web;

import com.astock.agent.analysis.MarketIndexService;
import com.astock.agent.analysis.MarketIndexSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market")
public final class MarketController {
    private final MarketIndexService service;

    public MarketController(MarketIndexService service) {
        this.service = service;
    }

    @GetMapping("/indices")
    public MarketIndexSnapshot indices() {
        return service.snapshot();
    }
}
```

- [x] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=MarketControllerTest' test`
Expected: PASS

---

### Task 5: 卡片两行布局与大盘条骨架

**Files:**
- Modify: `src/main/resources/static/workbench.html:54-86`
- Modify: `src/main/resources/static/styles.css:82-102, 290-343`

**Interfaces:**
- Produces: `#market-strip` 骨架（三个 `[data-benchmark]` 格子，含 `[data-field="point"|"change"|"state"]`），Task 6 负责填充

- [x] **Step 1: 修改 workbench.html**

在 `<section class="security-overview">` 内、`.security-identity` 之前插入：

```html
      <div class="market-strip" id="market-strip" aria-label="大盘指数">
        <div class="market-index" data-benchmark="SHANGHAI_COMPOSITE">
          <span class="market-index-name">上证指数</span>
          <span class="market-index-point" data-field="point">--</span>
          <span class="market-index-change" data-field="change">--</span>
          <small class="market-index-state" data-field="state" hidden></small>
        </div>
        <div class="market-index" data-benchmark="SHENZHEN_COMPONENT">
          <span class="market-index-name">深证成指</span>
          <span class="market-index-point" data-field="point">--</span>
          <span class="market-index-change" data-field="change">--</span>
          <small class="market-index-state" data-field="state" hidden></small>
        </div>
        <div class="market-index" data-benchmark="CHI_NEXT">
          <span class="market-index-name">创业板指</span>
          <span class="market-index-point" data-field="point">--</span>
          <span class="market-index-change" data-field="change">--</span>
          <small class="market-index-state" data-field="state" hidden></small>
        </div>
      </div>
```

同时更新资源版本号：

```html
  <link rel="stylesheet" href="/styles.css?v=20260914-market-strip">
  ...
  <script type="module" src="/js/app.js?v=20260914-market-strip"></script>
```

- [x] **Step 2: 修改 styles.css**

在 `.security-overview` 规则后追加：

```css
.market-strip { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px 20px; padding: 0 0 14px; border-bottom: 1px solid var(--hairline); }
.market-index { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
.market-index-name { flex: 0 0 auto; color: var(--muted); font-size: 12px; white-space: nowrap; }
.market-index-point { font-weight: 650; font-variant-numeric: tabular-nums; }
.market-index-change { font-variant-numeric: tabular-nums; }
.market-index-state { padding: 0 5px; border: 1px solid var(--hairline); border-radius: 4px; color: var(--muted); font-size: 10px; }
.market-index[data-state="unavailable"] .market-index-point,
.market-index[data-state="unavailable"] .market-index-change { color: var(--muted); font-weight: 500; }
```

`@media (max-width: 520px)` 块内追加：

```css
  .market-strip { grid-template-columns: 1fr; gap: 8px; }
  .market-index { justify-content: space-between; }
```

- [x] **Step 3: 本地视觉检查**

Run: `.\start.ps1` 后打开 `http://localhost:8080/workbench.html?code=600519`，确认第一行大盘条为空占位、第二行原有内容不挤压、1024/768/390 宽度无横向滚动。截图存档到 `target/market-strip-manual-*.png`。

- [x] **Step 4: 提交前离线静态检查**

Run: `npm.cmd run test:ui`（此时代码未接数据，只验证既有用例不被布局破坏）
Expected: 既有 UI 用例通过

---

### Task 6: 大盘条数据加载与渲染

**Files:**
- Modify: `src/main/resources/static/js/api.js:32-85`
- Modify: `src/main/resources/static/js/app.js:8, 21-49, 88-113, 482-532`

**Interfaces:**
- Consumes: `GET /api/market/indices`、`sectionPayload`、`formatNumber`
- Produces: `marketApi.indices()`；`renderMarketIndices(snapshot)`；`loadMarketIndices(isCurrent)`

- [x] **Step 1: api.js 增加 marketApi**

```js
export const marketApi = {
  indices() {
    return request('/api/market/indices');
  },
};
```

- [x] **Step 2: app.js 渲染函数**

导入改为：

```js
import { isPartialSnapshot, marketApi, sectionPayload, stockApi } from "./api.js";
```

在 `renderOverview` 之后新增：

```js
function renderMarketIndices(snapshot) {
  const sections = Array.isArray(snapshot?.indices) ? snapshot.indices : [];
  $$("#market-strip .market-index").forEach((node) => {
    const section = sections.find((item) => item?.benchmark === node.dataset.benchmark);
    const quote = sectionPayload(section?.quote);
    const status = section?.quote?.status || "UNAVAILABLE";
    const point = $('[data-field="point"]', node);
    const change = $('[data-field="change"]', node);
    const stateLabel = $('[data-field="state"]', node);
    node.dataset.state = status === "UNAVAILABLE" ? "unavailable" : "ready";
    node.title = section?.quote?.issues?.join("；") || "";
    clearTone(point, change);
    point.textContent = status === "UNAVAILABLE" ? "不可用"
      : quote?.lastPoint == null ? "--" : formatNumber(quote.lastPoint);
    const percent = Number(quote?.changePercent);
    change.textContent = quote?.changePercent == null ? ""
      : `${percent > 0 ? "↑ +" : percent < 0 ? "↓ " : ""}${formatNumber(percent)}%`;
    if (percent > 0) { point.classList.add("is-up"); change.classList.add("is-up"); }
    if (percent < 0) { point.classList.add("is-down"); change.classList.add("is-down"); }
    const fallback = (section?.quote?.issues || []).some((issue) => issue.includes("腾讯基准日线回退"));
    stateLabel.textContent = status === "STALE" ? "陈旧"
      : status !== "UNVERIFIED" ? "" : fallback ? "收盘口径" : "未验证";
    stateLabel.hidden = stateLabel.textContent === "";
  });
}

async function loadMarketIndices(isCurrent) {
  try {
    const snapshot = await marketApi.indices();
    if (!isCurrent()) return;
    renderMarketIndices(snapshot);
  } catch {
    if (!isCurrent()) return;
    $$("#market-strip .market-index").forEach((node) => {
      node.dataset.state = "unavailable";
      node.title = "大盘数据暂不可用";
      $('[data-field="point"]', node).textContent = "不可用";
      $('[data-field="change"]', node).textContent = "";
      $('[data-field="state"]', node).hidden = true;
    });
  }
}
```

- [x] **Step 3: 接入 loadStock**

在 `loadStock` 中 `state.currentCode = code;` 之后、（`setPhase("loading", ...)` 之前或之后均可）加入：

```js
  void loadMarketIndices(isCurrent);
```

- [x] **Step 4: 手动验证**

Run: `.\start.ps1` → `http://localhost:8080/workbench.html?code=600519`
Expected: 第一行显示三个指数的点位与涨跌幅（红涨绿跌 + ↑↓），刷新按钮同时刷新大盘；控制台无错误。

---

### Task 7: Playwright 大盘条测试

**Files:**
- Create: `tests/ui/fixtures/market-indices.json`
- Create: `tests/ui/market-indices.spec.js`

**Interfaces:**
- Consumes: `tests/ui/fixtures/partial-snapshot.json`
- Produces: 离线渲染断言 + 四视口截图（`target/market-strip-<width>.png`）

- [x] **Step 1: 写 fixture**

`tests/ui/fixtures/market-indices.json`：

```json
{
  "indices": [
    {
      "benchmark": "SHANGHAI_COMPOSITE",
      "displayName": "上证指数",
      "quote": {
        "status": "UNVERIFIED",
        "payload": { "benchmark": "SHANGHAI_COMPOSITE", "displayName": "上证指数", "lastPoint": 3123.45, "previousClose": 3113.2, "changeAmount": 10.25, "changePercent": 0.33, "quotedAt": "2026-09-14T01:36:13Z" },
        "provenance": { "provider": "HiThink Finance", "sourceUrl": "https://fuyao.aicubes.cn/api/a-share-index/prices/snapshot", "providerTimestamp": "2026-09-14T01:36:00Z", "fetchedAt": "2026-09-14T01:36:10Z", "cached": false, "fallbackProvider": null },
        "issues": ["同花顺指数行情源时间是数据就绪时间，不是成交时间"]
      }
    },
    {
      "benchmark": "SHENZHEN_COMPONENT",
      "displayName": "深证成指",
      "quote": {
        "status": "UNVERIFIED",
        "payload": { "benchmark": "SHENZHEN_COMPONENT", "displayName": "深证成指", "lastPoint": 10123.45, "previousClose": 10200, "changeAmount": -76.55, "changePercent": -0.75, "quotedAt": "2026-09-14T01:36:13Z" },
        "provenance": { "provider": "Tencent", "sourceUrl": "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get", "providerTimestamp": null, "fetchedAt": "2026-09-14T01:36:10Z", "cached": false, "fallbackProvider": "HiThink Finance" },
        "issues": ["腾讯基准日线回退：最新已完成交易日（2026-09-11）收盘口径，不是实时行情"]
      }
    },
    {
      "benchmark": "CHI_NEXT",
      "displayName": "创业板指",
      "quote": {
        "status": "UNAVAILABLE",
        "payload": null,
        "provenance": null,
        "issues": ["同花顺指数行情不可用；腾讯基准日线回退不可用"]
      }
    }
  ]
}
```

- [x] **Step 2: 写 UI 测试**

```js
const { test, expect } = require('@playwright/test');
const baseSnapshot = require('./fixtures/partial-snapshot.json');
const marketIndices = require('./fixtures/market-indices.json');

async function openWorkbench(page, indices) {
  await page.route('**/api/**', (route) => {
    const url = route.request().url();
    if (url.includes('/api/market/indices')) return route.fulfill({ json: indices });
    if (url.includes('/snapshot')) return route.fulfill({ json: baseSnapshot });
    return route.fulfill({ json: { status: 'UNAVAILABLE', enabled: false, issues: ['offline'] } });
  });
  await page.goto('/workbench.html');
  await page.locator('[data-symbol="600519"]').click();
  await expect(page.locator('#market-strip')).toBeVisible();
}

for (const viewport of [{width:1440,height:1000},{width:1024,height:768},{width:768,height:1024},{width:390,height:844}]) {
  test(`market strip renders indices at ${viewport.width}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openWorkbench(page, marketIndices);
    const strip = page.locator('#market-strip');
    await expect(strip.locator('[data-benchmark="SHANGHAI_COMPOSITE"]')).toContainText('3,123.45');
    await expect(strip.locator('[data-benchmark="SHANGHAI_COMPOSITE"]')).toContainText('↑ +0.33%');
    await expect(strip.locator('[data-benchmark="SHENZHEN_COMPONENT"]')).toContainText('↓ -0.75%');
    await expect(strip.locator('[data-benchmark="SHENZHEN_COMPONENT"] [data-field="state"]')).toHaveText('收盘口径');
    await expect(strip.locator('[data-benchmark="CHI_NEXT"]')).toContainText('不可用');
    const layout = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth }));
    expect(layout.scroll).toBeLessThanOrEqual(layout.width + 1);
    await page.screenshot({ path: `target/market-strip-${viewport.width}.png`, fullPage: true });
  });
}

test('market strip does not fabricate values when endpoint fails', async ({ page }) => {
  await page.route('**/api/**', (route) => {
    const url = route.request().url();
    if (url.includes('/api/market/indices')) return route.fulfill({ status: 500, json: { title: 'boom' } });
    if (url.includes('/snapshot')) return route.fulfill({ json: baseSnapshot });
    return route.fulfill({ json: { status: 'UNAVAILABLE', enabled: false, issues: ['offline'] } });
  });
  await page.goto('/workbench.html');
  await page.locator('[data-symbol="600519"]').click();
  const strip = page.locator('#market-strip');
  await expect(strip.locator('[data-benchmark="SHANGHAI_COMPOSITE"]')).toContainText('不可用');
  await expect(strip).not.toContainText('0.00');
});
```

- [x] **Step 3: 运行**

Run: `npm.cmd run test:ui`
Expected: 全部通过（含新增 5 个用例）

- [x] **Step 4: 检查截图**

打开 `target/market-strip-1440.png`、`target/market-strip-390.png`，确认三指数不换行错位、390 宽纵向堆叠、无横向溢出、与头部无重叠。

---

### Task 8: 文档、External 校验与全量验证

**Files:**
- Modify: `docs/hithink-integration.md`
- Modify: `src/test/java/com/astock/agent/external/HithinkLiveDataIT.java`

**Interfaces:**
- Consumes: `HithinkFinanceClient.fetchIndexQuotes`

- [x] **Step 1: 更新 hithink-integration.md**

在「数据路径与限制」列表追加一条：

```markdown
- 大盘指数快照优先同花顺指数快照接口（上证指数、深证成指、创业板指批量一次请求）；腾讯基准日线仅在单指数不可用时回退，回退口径为最新已完成交易日收盘并在 issues 说明。
```

- [x] **Step 2: 扩展 external live 用例**

在 `HithinkLiveDataIT.fetchesNormalizedAnnualHistoryAndValuation` 中追加：

```java
        var indices = client.fetchIndexQuotes(java.util.List.of(
                com.astock.agent.marketdata.model.BenchmarkId.SHANGHAI_COMPOSITE,
                com.astock.agent.marketdata.model.BenchmarkId.SHENZHEN_COMPONENT,
                com.astock.agent.marketdata.model.BenchmarkId.CHI_NEXT));
        assertThat(indices).hasSize(3);
        for (var section : indices) {
            assertThat(section.payload()).as("index quote: %s", section.issues()).isPresent();
            assertThat(section.payload().orElseThrow().lastPoint()).isNotNull();
        }
```

并把 `indices` 写入 `target/data-verification/hithink-600519.json` 的输出 map（key 用 `"indices"`）。

- [x] **Step 3: 运行 external live 校验（需本地已配置 key）**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Pexternal' '-Dtest=HithinkLiveDataIT' test`
Expected: PASS；检查 `target/data-verification/hithink-600519.json` 中指数 `lastPoint` 与 `price_change_ratio_pct` 非空。若字段缺失，保留空值并把真实字段名记录到 issue/文档，**不得**在测试或实现里假设字段存在。

- [x] **Step 4: 全量验证**

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-DskipTests' package
npm.cmd run test:ui
git diff --check
```

Expected: 全部通过；`git diff --check` 无输出；UI 截图人工检查通过。

- [x] **Step 5: 汇总结果并请求提交确认**

汇报：新增/修改文件清单、测试输出摘要、live 校验产物路径、截图路径；提交需用户确认。
