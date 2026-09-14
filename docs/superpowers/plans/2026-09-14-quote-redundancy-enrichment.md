# 行情字段级冗余补齐（Quote Redundancy Enrichment）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 个股快照不再因「同花顺缺字段」整体切换数据源：核心字段保留同花顺，缺失字段按 腾讯 → 东方财富 → 新浪 顺序逐字段补齐；同花顺整体不可用时按同一顺序整段回退。所有来源与补齐字段可追溯。

**Architecture:** 新增纯函数 `QuoteMerger` 负责字段级合并与来源记录；新增东财/新浪行情适配器（已实测字段映射）；`HithinkResearchGateway.quote()` 组装主源 + 有序候选源，主源可用走补齐，不可用走整段回退链。

**Tech Stack:** Java 21, JUnit 5 + AssertJ, MockMvc（如涉及）, 原生 JS（无展示层改动）。

**实测字段依据（2026-09-14，600519）：**

| 字段 | 同花顺 | 腾讯 | 东财 push2 | 新浪 hq |
|---|---|---|---|---|
| 现价/昨收/今开/最高/最低 | ✓ | ✓ | f43/f60/f46/f44/f45 | 字段 3/2/1/4/5 |
| 涨跌额/涨跌幅 | ✓ | [31]/[32] | f169/f170 | 由现价与昨收计算 |
| 成交量/成交额 | ✓(股/元) | [36]手→股/[37]万元→元 | f47手→股/f48元 | 8 股/9 元 |
| 换手率 | — | [38] | f168 | — |
| 振幅 | — | [43] | f171 | — |
| 量比 | — | [49] | f50 | — |
| PE TTM | 独立估值分区(pe_ttm) | [39] | f164（**不是 f162**） | — |
| 静态 PE | — | [52] | —（f162 口径未确认，禁用） | — |
| PB | 独立估值分区(pb_mrq，语义不同，不混用) | [46] | f167 | — |
| 总市值/流通市值 | — | [44]/[45]亿 | f116/f117 元 | — |
| 涨停/跌停 | — | [47]/[48] | f51/f52 | — |
| 数据时间 | —（源时间为就绪时间） | [30] | f86 秒 | 30/31 |

## Global Constraints

- 所有测试默认离线，外部用例标 `@Tag("external")` 且只在 `-Pexternal` 运行。
- 只在主值为 `null` 时补齐；绝不用候选值覆盖已有值；不补造数值；语义不同的字段（HiThink `pb_mrq` vs 行情 PB）不得混用。
- Provenance 必须保留：合并结果 provider 记为参与来源（如 `HiThink Finance + Tencent`），issues 说明每个来源补齐了哪些字段。
- 东财请求必须走共享 `ProviderThrottle`（已由 `ProviderHttpClient` 保证），不得并发。
- 门禁命令：
  `$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path; $env:Path = "$env:JAVA_HOME\bin;$env:Path"; .\mvnw.cmd '-Dmaven.repo.local=.m2/repository' <goal>`
- 提交需用户确认。

## 本轮范围与后续冗余清单

本轮：个股行情字段级冗余（同花顺 + 腾讯 + 东财 + 新浪）。
已有多源（无需改动）：K 线（同花顺/腾讯/百度）、资金流（东财/新浪）、分红与龙虎榜（同花顺/东财）、财报（同花顺/新浪）、大盘指数（同花顺/腾讯）。
后续候选（本轮不做，避免不可验证的映射）：两融/大宗/股东户数/解禁（skill 备胎为交易所官方）、行业对比、研报（东财/同花顺一致预期/iwencai）、新闻（东财/财联社）、公告（巨潮/交易所官方）。

---

### Task 1: QuoteMerger 纯函数与单元测试

**Files:**
- Create: `src/main/java/com/astock/agent/analysis/QuoteMerger.java`
- Create: `src/test/java/com/astock/agent/analysis/QuoteMergerTest.java`

**Interfaces:**
- Produces:
  - `record QuoteMerger.Source(String provider, Quote quote)`
  - `record QuoteMerger.Outcome(Quote quote, Map<String,List<String>> filledByProvider)`
  - `static Outcome merge(Quote base, List<Source> sources)`

- [x] **Step 1: 写失败测试**

```java
package com.astock.agent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteMergerTest {
    private final SecurityId security = SecurityId.parse("600519");

    @Test void fillsOnlyNullFieldsAndKeepsPrimaryValues() {
        Quote primary = quote(new BigDecimal("100"), null, null);
        Quote tencent = quote(new BigDecimal("999"), new BigDecimal("10"), new BigDecimal("1.2"));
        var outcome = QuoteMerger.merge(primary, List.of(new QuoteMerger.Source("Tencent", tencent)));
        assertThat(outcome.quote().price()).isEqualByComparingTo("100");
        assertThat(outcome.quote().peTtm()).isEqualByComparingTo("10");
        assertThat(outcome.quote().pb()).isEqualByComparingTo("1.2");
        assertThat(outcome.filledByProvider()).containsOnlyKeys("Tencent");
        assertThat(outcome.filledByProvider().get("Tencent")).containsExactly("市盈率TTM", "市净率");
    }

    @Test void laterSourcesFillWhatEarlierSourcesCannot() {
        Quote empty = quote(null, null, null);
        Quote tencent = quote(null, new BigDecimal("10"), null);
        Quote eastmoney = quote(null, new BigDecimal("11"), new BigDecimal("1.5"));
        var outcome = QuoteMerger.merge(empty, List.of(
                new QuoteMerger.Source("Tencent", tencent),
                new QuoteMerger.Source("Eastmoney", eastmoney)));
        assertThat(outcome.quote().peTtm()).isEqualByComparingTo("10");
        assertThat(outcome.quote().pb()).isEqualByComparingTo("1.5");
        assertThat(outcome.filledByProvider().keySet()).containsExactly("Tencent", "Eastmoney");
        assertThat(outcome.filledByProvider().get("Eastmoney")).containsExactly("市净率");
    }

    @Test void recordsNothingWhenNoFieldWasFilled() {
        Quote complete = quote(new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("1.2"));
        var outcome = QuoteMerger.merge(complete, List.of(
                new QuoteMerger.Source("Tencent", quote(new BigDecimal("9"), null, null))));
        assertThat(outcome.quote()).isEqualTo(complete);
        assertThat(outcome.filledByProvider()).isEmpty();
    }

    private Quote quote(BigDecimal price, BigDecimal peTtm, BigDecimal pb) {
        return new Quote(security, "贵州茅台", price, new BigDecimal("99"), new BigDecimal("98"),
                new BigDecimal("101"), new BigDecimal("97"), null, null, null, null,
                null, null, null, peTtm, null, pb, null, null, null, null, Instant.parse("2026-09-14T01:00:00Z"));
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=QuoteMergerTest' test`
Expected: 编译失败（QuoteMerger 不存在）

- [x] **Step 3: 实现**

```java
package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.Quote;
import java.util.*;
import java.util.function.Function;

/** 字段级主备合并：只在主值为空时补齐，并记录每个来源补了哪些字段。 */
public final class QuoteMerger {

    public record Source(String provider, Quote quote) {}
    public record Outcome(Quote quote, Map<String, List<String>> filledByProvider) {}

    private static final Map<String, Function<Quote, Object>> READERS = readers();

    private static Map<String, Function<Quote, Object>> readers() {
        var map = new LinkedHashMap<String, Function<Quote, Object>>();
        map.put("现价", Quote::price);
        map.put("昨收", Quote::previousClose);
        map.put("今开", Quote::open);
        map.put("最高", Quote::high);
        map.put("最低", Quote::low);
        map.put("涨跌额", Quote::changeAmount);
        map.put("涨跌幅", Quote::changePercent);
        map.put("成交量", Quote::volumeShares);
        map.put("成交额", Quote::amountYuan);
        map.put("换手率", Quote::turnoverPercent);
        map.put("振幅", Quote::amplitudePercent);
        map.put("量比", Quote::volumeRatio);
        map.put("市盈率TTM", Quote::peTtm);
        map.put("静态市盈率", Quote::peStatic);
        map.put("市净率", Quote::pb);
        map.put("总市值", Quote::totalMarketValueYuan);
        map.put("流通市值", Quote::circulatingMarketValueYuan);
        map.put("涨停价", Quote::limitUp);
        map.put("跌停价", Quote::limitDown);
        map.put("数据时间", Quote::quotedAt);
        return Collections.unmodifiableMap(map);
    }

    public static Outcome merge(Quote base, List<Source> sources) {
        Quote current = base;
        var filled = new LinkedHashMap<String, List<String>>();
        for (Source source : sources) {
            if (source == null || source.quote() == null) continue;
            Quote before = current;
            current = fill(before, source.quote());
            List<String> fields = changedFields(before, current);
            if (!fields.isEmpty()) filled.put(source.provider(), fields);
        }
        return new Outcome(current, Collections.unmodifiableMap(filled));
    }

    private static List<String> changedFields(Quote before, Quote after) {
        var names = new ArrayList<String>();
        READERS.forEach((label, reader) -> {
            Object oldValue = reader.apply(before);
            Object newValue = reader.apply(after);
            boolean wasBlank = oldValue == null || (oldValue instanceof java.math.BigDecimal d && d.signum() == 0);
            boolean nowPresent = newValue != null && !(newValue instanceof java.math.BigDecimal d && d.signum() == 0);
            if (wasBlank && nowPresent && !newValue.equals(oldValue)) names.add(label);
        });
        return List.copyOf(names);
    }

    private static Quote fill(Quote base, Quote candidate) {
        return new Quote(
                base.security(), base.name(),
                pick(base.price(), candidate.price()),
                pick(base.previousClose(), candidate.previousClose()),
                pick(base.open(), candidate.open()),
                pick(base.high(), candidate.high()),
                pick(base.low(), candidate.low()),
                pick(base.changeAmount(), candidate.changeAmount()),
                pick(base.changePercent(), candidate.changePercent()),
                pick(base.volumeShares(), candidate.volumeShares()),
                pick(base.amountYuan(), candidate.amountYuan()),
                pick(base.turnoverPercent(), candidate.turnoverPercent()),
                pick(base.amplitudePercent(), candidate.amplitudePercent()),
                pick(base.volumeRatio(), candidate.volumeRatio()),
                pick(base.peTtm(), candidate.peTtm()),
                pick(base.peStatic(), candidate.peStatic()),
                pick(base.pb(), candidate.pb()),
                pick(base.totalMarketValueYuan(), candidate.totalMarketValueYuan()),
                pick(base.circulatingMarketValueYuan(), candidate.circulatingMarketValueYuan()),
                pick(base.limitUp(), candidate.limitUp()),
                pick(base.limitDown(), candidate.limitDown()),
                base.quotedAt() != null ? base.quotedAt() : candidate.quotedAt());
    }

    private static <T> T pick(T base, T candidate) {
        return base == null ? candidate : base;
    }
}
```

- [x] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=QuoteMergerTest' test`
Expected: PASS

---

### Task 2: 东财行情适配器

**Files:**
- Modify: `src/main/java/com/astock/agent/marketdata/provider/eastmoney/EastmoneyResearchClient.java`
- Create: `src/test/resources/fixtures/eastmoney/quote-600519.json`
- Create: `src/test/java/com/astock/agent/marketdata/provider/EastmoneyQuoteTest.java`

**Interfaces:**
- Produces: `EastmoneyResearchClient.fetchQuote(SecurityId)` → `DataSection<Quote>`；`parseQuote(String body, SecurityId)`（public 供 fixture 测试）

- [x] **Step 1: fixture**

`src/test/resources/fixtures/eastmoney/quote-600519.json`：

```json
{"rc":0,"data":{"f43":1275.38,"f44":1285.53,"f45":1270.36,"f46":1277.27,"f47":13333,"f48":1702935275.0,"f50":0.65,"f51":1402.68,"f52":1147.64,"f57":"600519","f58":"贵州茅台","f60":1275.16,"f86":1789366157,"f116":1594329072283.3801,"f117":1594329072283.3801,"f164":19.58,"f167":6.35,"f168":0.11,"f169":0.22,"f170":0.02,"f171":1.19}}
```

- [x] **Step 2: 写失败测试**

```java
package com.astock.agent.marketdata.provider;

import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.eastmoney.EastmoneyResearchClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EastmoneyQuoteTest {
    private final EastmoneyResearchClient client = new EastmoneyResearchClient();

    @Test void parsesVerifiedUnitsAndIdentity() throws Exception {
        String body = Files.readString(Path.of("src/test/resources/fixtures/eastmoney/quote-600519.json"),
                StandardCharsets.UTF_8);
        Quote quote = client.parseQuote(body, SecurityId.parse("600519"));
        assertThat(quote.name()).isEqualTo("贵州茅台");
        assertThat(quote.price()).isEqualByComparingTo("1275.38");
        assertThat(quote.previousClose()).isEqualByComparingTo("1275.16");
        assertThat(quote.open()).isEqualByComparingTo("1277.27");
        assertThat(quote.high()).isEqualByComparingTo("1285.53");
        assertThat(quote.low()).isEqualByComparingTo("1270.36");
        assertThat(quote.changeAmount()).isEqualByComparingTo("0.22");
        assertThat(quote.changePercent()).isEqualByComparingTo("0.02");
        assertThat(quote.volumeShares()).isEqualByComparingTo("1333300");
        assertThat(quote.amountYuan()).isEqualByComparingTo("1702935275.0");
        assertThat(quote.turnoverPercent()).isEqualByComparingTo("0.11");
        assertThat(quote.amplitudePercent()).isEqualByComparingTo("1.19");
        assertThat(quote.volumeRatio()).isEqualByComparingTo("0.65");
        assertThat(quote.peTtm()).isEqualByComparingTo("19.58");
        assertThat(quote.peStatic()).isNull();
        assertThat(quote.pb()).isEqualByComparingTo("6.35");
        assertThat(quote.totalMarketValueYuan()).isEqualByComparingTo("1594329072283.3801");
        assertThat(quote.circulatingMarketValueYuan()).isEqualByComparingTo("1594329072283.3801");
        assertThat(quote.limitUp()).isEqualByComparingTo("1402.68");
        assertThat(quote.limitDown()).isEqualByComparingTo("1147.64");
        assertThat(quote.quotedAt()).isEqualTo(java.time.Instant.parse("2026-09-14T06:09:17Z"));
    }

    @Test void rejectsWrongIdentityAndBrokenOhlc() throws Exception {
        String body = Files.readString(Path.of("src/test/resources/fixtures/eastmoney/quote-600519.json"),
                StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.parseQuote(body, SecurityId.parse("000001")))
                .isInstanceOf(IllegalArgumentException.class);
        String broken = body.replace("\"f44\":1285.53", "\"f44\":1260.0");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.parseQuote(broken, SecurityId.parse("600519")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [x] **Step 3: 运行确认失败**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=EastmoneyQuoteTest' test`
Expected: 编译失败（parseQuote 不存在）

- [x] **Step 4: 实现（EastmoneyResearchClient）**

```java
    public DataSection<Quote> fetchQuote(SecurityId security) {
        ensureLiveClient();
        URI uri = URI.create("https://push2.eastmoney.com/api/qt/stock/get?fltt=2&invt=2&fields="
                + "f43,f44,f45,f46,f47,f48,f50,f51,f52,f57,f58,f60,f86,f116,f117,f164,f167,f168,f169,f170,f171"
                + "&secid=" + security.eastmoneySecId());
        try {
            Quote quote = parseQuote(http.get(ProviderId.EASTMONEY, uri,
                    "https://quote.eastmoney.com/").utf8Text(), security);
            return DataSection.healthy(quote, provenance(uri));
        } catch (Exception exception) {
            return DataSection.unavailable("Eastmoney quote failed: " + exception.getMessage());
        }
    }

    /** Field mapping verified 2026-09-14 against Tencent for 600519; f164 is PE TTM, f162 is a different measure. */
    public Quote parseQuote(String body, SecurityId security) {
        try {
            JsonNode data = MAPPER.readTree(body).path("data");
            if (!security.code().equals(data.path("f57").asText()))
                throw new IllegalArgumentException("Eastmoney quote identity mismatch");
            BigDecimal price = positive(data, "f43");
            BigDecimal previous = positive(data, "f60");
            BigDecimal open = positive(data, "f46");
            BigDecimal high = positive(data, "f44");
            BigDecimal low = positive(data, "f45");
            if (high.compareTo(open.max(price)) < 0 || low.compareTo(open.min(price)) > 0)
                throw new IllegalArgumentException("Eastmoney quote OHLC relationship invalid");
            return new Quote(security, data.path("f58").asText(null), price, previous, open, high, low,
                    number(data, "f169"), number(data, "f170"),
                    multiply(data, "f47", new BigDecimal("100")), number(data, "f48"),
                    number(data, "f168"), number(data, "f171"), number(data, "f50"),
                    number(data, "f164"), null, number(data, "f167"),
                    number(data, "f116"), number(data, "f117"),
                    number(data, "f51"), number(data, "f52"), quoteTime(data, "f86"));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse Eastmoney quote", exception);
        }
    }

    private static BigDecimal positive(JsonNode data, String field) {
        BigDecimal value = number(data, field);
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("Missing Eastmoney price field " + field);
        return value;
    }

    private static BigDecimal number(JsonNode data, String field) {
        JsonNode node = data.path(field);
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalArgumentException("Non-numeric Eastmoney field " + field);
        return node.decimalValue();
    }

    private static BigDecimal multiply(JsonNode data, String field, BigDecimal factor) {
        BigDecimal value = number(data, field);
        return value == null ? null : value.multiply(factor);
    }

    private static Instant quoteTime(JsonNode data, String field) {
        JsonNode node = data.path(field);
        if (node.isMissingNode() || node.isNull() || !node.isIntegralNumber() || node.asLong() <= 0) return null;
        return Instant.ofEpochSecond(node.asLong());
    }
```

同时确认 `EastmoneyResearchClient` 顶部已导入 `Instant`、`BigDecimal`、`Quote`；缺什么补什么。

- [x] **Step 5: 运行确认通过**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=EastmoneyQuoteTest' test`
Expected: PASS

---

### Task 3: 新浪行情适配器

**Files:**
- Modify: `src/main/java/com/astock/agent/marketdata/provider/sina/SinaFinanceClient.java`
- Create: `src/test/resources/fixtures/sina/quote-600519.txt`
- Create: `src/test/java/com/astock/agent/marketdata/provider/SinaQuoteTest.java`

**Interfaces:**
- Produces: `SinaFinanceClient.fetchQuote(SecurityId)` → `DataSection<Quote>`；`parseQuote(String body, SecurityId)`

- [x] **Step 1: fixture（真实格式）**

`src/test/resources/fixtures/sina/quote-600519.txt`：

```text
var hq_str_sh600519="贵州茅台,1277.270,1275.160,1275.580,1285.530,1270.360,1275.590,1275.600,1334650,1704720964.000,100,1275.590,100,1275.510,100,1275.500,100,1275.490,100,1275.470,100,1275.600,100,1275.880,200,1275.890,200,1275.900,100,1275.910,100,1275.920,2026-09-14,14:09:56,00,";
```

- [x] **Step 2: 写失败测试**

```java
package com.astock.agent.marketdata.provider;

import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.sina.SinaFinanceClient;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SinaQuoteTest {
    private final SinaFinanceClient client = new SinaFinanceClient();

    @Test void parsesGb2312QuoteAndComputesChangeFromPriceAndPreviousClose() throws Exception {
        String body = Files.readString(Path.of("src/test/resources/fixtures/sina/quote-600519.txt"),
                Charset.forName("GBK"));
        Quote quote = client.parseQuote(body, SecurityId.parse("600519"));
        assertThat(quote.name()).isEqualTo("贵州茅台");
        assertThat(quote.open()).isEqualByComparingTo("1277.270");
        assertThat(quote.previousClose()).isEqualByComparingTo("1275.160");
        assertThat(quote.price()).isEqualByComparingTo("1275.580");
        assertThat(quote.high()).isEqualByComparingTo("1285.530");
        assertThat(quote.low()).isEqualByComparingTo("1270.360");
        assertThat(quote.volumeShares()).isEqualByComparingTo("1334650");
        assertThat(quote.amountYuan()).isEqualByComparingTo("1704720964.000");
        assertThat(quote.changeAmount()).isEqualByComparingTo("0.42");
        assertThat(quote.changePercent()).isEqualByComparingTo("0.0329");
        assertThat(quote.turnoverPercent()).isNull();
        assertThat(quote.peTtm()).isNull();
        assertThat(quote.quotedAt()).isEqualTo(java.time.Instant.parse("2026-09-14T06:09:56Z"));
    }

    @Test void rejectsIdentityMismatch() throws Exception {
        String body = Files.readString(Path.of("src/test/resources/fixtures/sina/quote-600519.txt"),
                Charset.forName("GBK"));
        assertThatThrownBy(() -> client.parseQuote(body, SecurityId.parse("000001")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [x] **Step 3: 运行确认失败** → 编译失败

- [x] **Step 4: 实现**

```java
    public DataSection<Quote> fetchQuote(SecurityId security) {
        ensureLiveClient();
        String symbol = (security.exchange() == Exchange.SHANGHAI ? "sh" : "sz") + security.code();
        URI uri = URI.create("https://hq.sinajs.cn/list=" + symbol);
        try {
            var response = http.get(ProviderId.SINA, uri, "https://finance.sina.com.cn/");
            Quote quote = parseQuote(response.text(Charset.forName("GBK")), security);
            return DataSection.healthy(quote, provenance(uri));
        } catch (Exception exception) {
            return DataSection.unavailable("Sina quote failed: " + exception.getMessage());
        }
    }

    /** Sina hq.sinajs.cn only carries core price fields; valuation fields stay null on purpose. */
    public Quote parseQuote(String body, SecurityId security) {
        try {
            String symbol = (security.exchange() == Exchange.SHANGHAI ? "sh" : "sz") + security.code();
            if (!body.contains("hq_str_" + symbol + "="))
                throw new IllegalArgumentException("Sina quote identity mismatch");
            int first = body.indexOf('"');
            int last = body.lastIndexOf('"');
            if (first < 0 || last <= first) throw new IllegalArgumentException("Sina quote payload malformed");
            String[] values = body.substring(first + 1, last).split(",", -1);
            if (values.length < 32) throw new IllegalArgumentException("Sina quote field count mismatch");
            BigDecimal previous = positive(values[2]);
            BigDecimal price = positive(values[3]);
            BigDecimal open = positive(values[1]);
            BigDecimal high = positive(values[4]);
            BigDecimal low = positive(values[5]);
            if (high.compareTo(open.max(price)) < 0 || low.compareTo(open.min(price)) > 0)
                throw new IllegalArgumentException("Sina quote OHLC relationship invalid");
            BigDecimal change = price.subtract(previous);
            BigDecimal percent = change.multiply(BigDecimal.valueOf(100))
                    .divide(previous, 4, java.math.RoundingMode.HALF_UP);
            Instant quotedAt = LocalDateTime.parse(values[30] + "T" + values[31])
                    .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            return new Quote(security, values[0], price, previous, open, high, low,
                    change, percent, decimal(values[8]), decimal(values[9]),
                    null, null, null, null, null, null, null, null, null, null, quotedAt);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse Sina quote", exception);
        }
    }

    private static BigDecimal positive(String value) {
        BigDecimal number = decimal(value);
        if (number == null || number.signum() <= 0) throw new IllegalArgumentException("Missing Sina price field");
        return number;
    }
```

（`decimal(String)` 若类中已有则复用；需要 `java.nio.charset.Charset`、`java.time.Instant/ZoneId/LocalDateTime`、`Exchange` 导入。测试断言 `0.0329` 取决于 scale 4 HALF_UP：0.42/1275.16*100=0.032937...→0.0329。）

- [x] **Step 5: 运行确认通过**

---

### Task 4: 网关组装主源 + 有序候选（补齐与整段回退）

**Files:**
- Modify: `src/main/java/com/astock/agent/analysis/HithinkResearchGateway.java`
- Modify: `src/main/java/com/astock/agent/config/MarketDataConfiguration.java`
- Modify: `src/test/java/com/astock/agent/analysis/HithinkResearchGatewayTest.java`

**Interfaces:**
- Consumes: `QuoteMerger`、`EastmoneyResearchClient.fetchQuote`、`SinaFinanceClient.fetchQuote`
- Produces: `HithinkResearchGateway(ResearchGateway primary, HithinkFinanceClient hithink, List<Function<SecurityId,DataSection<Quote>>> enrichments)`；`quote()` 合并语义

- [x] **Step 1: 写失败测试**（在 `HithinkResearchGatewayTest` 追加；使用本地 HttpServer + 假 enrichment）

```java
    @Test void enrichesMissingQuoteFieldsWithoutReplacingPrimaryValues() {
        body.set("""
                {"code":0,"data":{"timestamp":1789081200000,"item":[
                {"thscode":"600519.SH","ticker":"600519","name":"贵州茅台","last_price":100,
                 "prev_price":99,"open_price":98,"high_price":101,"low_price":97}]}}
                """);
        java.util.function.Function<SecurityId, DataSection<Quote>> tencent = ignored ->
                DataSection.healthy(candidateQuote(new BigDecimal("999"), new BigDecimal("19.58"),
                        new BigDecimal("6.35"), new BigDecimal("0.11")),
                        new Provenance("Tencent", URI.create("https://qt.gtimg.cn/q=sh600519"),
                                null, clock.instant(), false, null));
        var gateway = new HithinkResearchGateway(primaryStub(), client, List.of(tencent));
        var section = gateway.quote(SecurityId.parse("600519"));
        assertThat(section.payload().orElseThrow().price()).isEqualByComparingTo("100");
        assertThat(section.payload().orElseThrow().peTtm()).isEqualByComparingTo("19.58");
        assertThat(section.payload().orElseThrow().pb()).isEqualByComparingTo("6.35");
        assertThat(section.payload().orElseThrow().turnoverPercent()).isEqualByComparingTo("0.11");
        assertThat(section.provenance().orElseThrow().provider()).isEqualTo("HiThink Finance + Tencent");
        assertThat(section.issues()).anyMatch(s -> s.contains("腾讯") && s.contains("市盈率TTM"));
    }

    @Test void fallsBackWholeSectionThroughOrderedSourcesWhenPrimaryUnavailable() {
        body.set("{\"code\":1,\"message\":\"denied\"}");
        var eastmoney = enrichment("Eastmoney", candidateQuote(new BigDecimal("1275"), new BigDecimal("19.58"),
                new BigDecimal("6.35"), new BigDecimal("0.11")));
        var unavailableTencent = enrichmentUnavailable("Tencent");
        var gateway = new HithinkResearchGateway(primaryStub(), client,
                List.of(unavailableTencent, eastmoney));
        var section = gateway.quote(SecurityId.parse("600519"));
        assertThat(section.status()).isEqualTo(SectionStatus.DEGRADED);
        assertThat(section.payload().orElseThrow().price()).isEqualByComparingTo("1275");
        assertThat(section.provenance().orElseThrow().provider()).isEqualTo("Eastmoney");
        assertThat(section.provenance().orElseThrow().fallbackProvider()).isEqualTo("HiThink Finance");
        assertThat(section.issues()).anyMatch(s -> s.contains("同花顺"));
    }
```

辅助方法与既有测试类共用 `client`、`clock`、`body`；`primaryStub()` 返回 `security -> DataSection.unavailable("stub")`（quote 路径不会用到它，除非 enrichment 全部失败，可再加一个 `allSourcesFail` 用例断言 UNAVAILABLE）。

- [x] **Step 2: 运行确认失败** → 新构造器不存在

- [x] **Step 3: 实现网关**

```java
    private final List<java.util.function.Function<SecurityId, DataSection<Quote>>> enrichments;

    public HithinkResearchGateway(ResearchGateway primary, HithinkFinanceClient hithink) {
        this(primary, hithink, List.of(primary::quote));
    }

    public HithinkResearchGateway(ResearchGateway primary, HithinkFinanceClient hithink,
            List<java.util.function.Function<SecurityId, DataSection<Quote>>> enrichments) {
        this.primary = primary;
        this.hithink = hithink;
        this.enrichments = List.copyOf(enrichments);
    }

    @Override public DataSection<Quote> quote(SecurityId security) {
        DataSection<Quote> main = hithink.fetchQuote(security);
        List<QuoteMerger.Source> candidates = loadCandidates(security);
        if (hasData(main) && main.status() != SectionStatus.STALE) {
            return enrichQuote(main, candidates);
        }
        var issues = new ArrayList<>(main.issues());
        for (DataSection<Quote> candidate : rawCandidates(security)) {
            if (!hasData(candidate)) { issues.addAll(candidate.issues()); continue; }
            var merged = QuoteMerger.merge(candidate.payload().orElseThrow(), otherCandidates(candidate, candidates))
                    .quote();
            issues.add("同花顺行情不可用或陈旧，使用 " + candidate.provenance().orElseThrow().provider() + " 行情");
            Provenance p = candidate.provenance().orElseThrow();
            var source = new Provenance(p.provider(), p.sourceUrl(), p.providerTimestamp(), p.fetchedAt(),
                    p.cached(), "HiThink Finance");
            return new DataSection<>(SectionStatus.DEGRADED, Optional.of(merged), Optional.of(source), issues);
        }
        return DataSection.unavailable(String.join("；", issues.isEmpty() ? List.of("所有行情来源均不可用") : issues));
    }

    private DataSection<Quote> enrichQuote(DataSection<Quote> main, List<QuoteMerger.Source> candidates) {
        if (candidates.isEmpty()) return main;
        var outcome = QuoteMerger.merge(main.payload().orElseThrow(), candidates);
        if (outcome.filledByProvider().isEmpty()) return main;
        var issues = new ArrayList<>(main.issues());
        outcome.filledByProvider().forEach((provider, fields) ->
                issues.add(provider + " 补齐 " + String.join("、", fields)));
        Provenance p = main.provenance().orElseThrow();
        var source = new Provenance(
                p.provider() + " + " + String.join(" + ", outcome.filledByProvider().keySet()),
                p.sourceUrl(), p.providerTimestamp(), p.fetchedAt(), p.cached(), null);
        return new DataSection<>(main.status(), Optional.of(outcome.quote()), Optional.of(source), issues);
    }
```

（`loadCandidates`/`rawCandidates` 负责调用 enrichment、捕获异常、按顺序返回 `usable` 的 `QuoteMerger.Source`，并保留原始 section 供整段回退使用；实现时拆成两个私有方法，命名保持一致。）

- [x] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=HithinkResearchGatewayTest,QuoteMergerTest' test`

- [x] **Step 5: 配置装配**

```java
    @Bean
    com.astock.agent.analysis.HithinkResearchGateway researchGateway(
            ... 现有参数 ...,
            EastmoneyResearchClient eastmoney, SinaFinanceClient sina) {
        return new com.astock.agent.analysis.HithinkResearchGateway(providerGateway, hithink, List.of(
                providerGateway::quote, eastmoney::fetchQuote, sina::fetchQuote));
    }
```

（以 `MarketDataConfiguration` 现有 `researchGateway` bean 为准，只追加两个来源；`providerGateway::quote` 即腾讯。）

---

### Task 5: 修复股东户数字段映射

**Files:**
- Modify: `src/main/java/com/astock/agent/marketdata/provider/eastmoney/EastmoneyResearchClient.java:156`
- Modify: `src/test/resources/fixtures/eastmoney/capital-events-600519.json`
- Modify: 引用该 fixture 的测试

- [x] **Step 1: 写/改失败测试**

fixture 的 holders 行改为真实字段名：

```json
"holders":[{"END_DATE":"2026-06-30","HOLDER_NUM":296404,"HOLDER_NUM_RATIO":21.897,"AVG_HOLD_NUM":4217.49}],
```

测试断言 `averageShares` 为 `4217.49`（当前实现产出 null → 先红）。

- [x] **Step 2: 改实现** → `decimal(item, "AVG_FREE_SHARES")` 改为 `decimal(item, "AVG_HOLD_NUM")`
- [x] **Step 3: 运行聚焦测试通过**

---

### Task 6: 外部实盘校验、文档与全量验证

**Files:**
- Create: `src/test/java/com/astock/agent/external/QuoteSourcesLiveIT.java`
- Modify: `docs/hithink-integration.md`

- [x] **Step 1: 外部用例**

```java
@Tag("external")
@SpringBootTest(properties = "spring.ai.model.chat=none")
class QuoteSourcesLiveIT {
    @Autowired EastmoneyResearchClient eastmoney;
    @Autowired SinaFinanceClient sina;
    @Autowired HithinkFinanceClient hithink;

    @Test void eastmoneyAndSinaReturnVerifiedCoreFieldsFor600519() {
        var security = SecurityId.parse("600519");
        var em = eastmoney.fetchQuote(security);
        assertThat(em.payload()).as("eastmoney: %s", em.issues()).isPresent();
        assertThat(em.payload().orElseThrow().price()).isNotNull();
        assertThat(em.payload().orElseThrow().peTtm()).isNotNull();
        var sinaQuote = sina.fetchQuote(security);
        assertThat(sinaQuote.payload()).as("sina: %s", sinaQuote.issues()).isPresent();
        assertThat(sinaQuote.payload().orElseThrow().price()).isNotNull();
        var hithinkQuote = hithink.fetchQuote(security);
        assertThat(hithinkQuote.payload()).isPresent();
        // 现价一致性：三方来源与同花顺差距应在 2% 以内（同一交易时段）。
        var reference = hithinkQuote.payload().orElseThrow().price();
        assertThat(em.payload().orElseThrow().price().subtract(reference).abs()
                .divide(reference, 4, RoundingMode.HALF_UP)).isLessThan(new BigDecimal("0.02"));
        assertThat(sinaQuote.payload().orElseThrow().price().subtract(reference).abs()
                .divide(reference, 4, RoundingMode.HALF_UP)).isLessThan(new BigDecimal("0.02"));
    }
}
```

- [x] **Step 2: 文档**
  `docs/hithink-integration.md` 的「数据路径与限制」追加：`个股行情以同花顺为主源，缺失字段按 腾讯 → 东方财富 → 新浪 顺序补齐；同花顺整体不可用时按同一顺序整段回退，合并来源与补齐字段写入 issues。`

- [x] **Step 3: 实盘校验**

Run: `.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Pexternal' '-Dtest=QuoteSourcesLiveIT' test`
Expected: PASS；若字段缺失按 issues 记录并回到 Task 2/3 修正映射（不得改断言迁就）。

- [x] **Step 4: 全量验证**

```
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-DskipTests' package
npm.cmd run test:ui
git diff --check
```

- [x] **Step 5: 汇总并请求提交确认**
