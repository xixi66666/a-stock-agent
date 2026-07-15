# A-Stock Spring AI Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a self-contained Java 21 Spring AI stock research agent that retrieves and validates real A-share data, calculates professional technical indicators, exposes evidence through REST APIs, and renders the approved light-purple dashboard.

**Architecture:** A modular Spring Boot monolith separates provider adapters, normalized market-data records, technical analysis, research aggregation, Spring AI tools, and the web UI. Provider failures remain section-local, every datum carries provenance, and the model can only call bounded tools over normalized data.

**Tech Stack:** Java 21.0.11+10, Maven 3.9.11 Wrapper, Spring Boot 3.5.16, Spring AI 1.1.8, ta4j 0.23.0, Caffeine, ECharts 6.1.0 WebJar, Lucide 1.16.0 WebJar, JUnit 5, AssertJ, MockWebServer, MockMvc, Playwright CLI.

---

## File Map

### Build And Runtime

- `pom.xml`: dependency management, compiler, unit/integration-test profiles, and build metadata.
- `.mvn/wrapper/maven-wrapper.properties`, `mvnw`, `mvnw.cmd`: pinned Maven 3.9.11 wrapper.
- `scripts/toolchain.properties`: pinned JDK archive URLs and SHA-256 values.
- `scripts/bootstrap-jdk.ps1`, `scripts/bootstrap-jdk.sh`: project-local Java bootstrap.
- `start.ps1`, `start.sh`: one-command application startup using local Java and Maven repository.
- `config/application-local.yml.example`: OpenAI-compatible secret configuration template.

### Core Domain

- `marketdata/model/SecurityId.java`: normalized six-digit code and exchange.
- `marketdata/model/Provenance.java`: provider, timestamps, URL, cache and fallback metadata.
- `marketdata/model/DataSection.java`: typed payload plus section status and validation issues.
- `marketdata/model/Quote.java`, `DailyBar.java`, `CompanyProfile.java`, `CapitalData.java`, `FundamentalData.java`, `EventData.java`: normalized provider-independent records.
- `marketdata/validation/MarketDataValidator.java`: identity, OHLC, timestamp, unit and consistency validation.

### Provider Infrastructure

- `marketdata/provider/MarketDataProvider.java`: capability-neutral provider contract.
- `marketdata/provider/ProviderHttpClient.java`: bounded Java HTTP access and response decoding.
- `marketdata/provider/ProviderThrottle.java`: serialized Eastmoney requests and jitter.
- `marketdata/provider/ProviderHealthRegistry.java`: cooldown and last-success state.
- `marketdata/provider/tencent/TencentMarketDataClient.java`: search, quote, benchmark and adjusted bars.
- `marketdata/provider/baidu/BaiduKlineClient.java`: independent daily bars and MA cross-check.
- `marketdata/provider/eastmoney/EastmoneyResearchClient.java`: sectors, fund flow, research, news and capital events.
- `marketdata/provider/sina/SinaFinanceClient.java`: statements and fund-flow fallback.
- `marketdata/provider/cninfo/CninfoAnnouncementClient.java`: official announcement search.

### Analysis And Agent

- `technical/TechnicalAnalysisService.java`: ta4j-backed indicator calculation.
- `technical/TechnicalSnapshot.java`, `IndicatorCard.java`: complete technical API model.
- `technical/CustomIndicators.java`: only formulas absent from ta4j.
- `analysis/DataQualityScorer.java`: transparent 0-100 quality breakdown.
- `analysis/ResearchAggregationService.java`: partial-success orchestration and caching.
- `analysis/StockResearchSnapshot.java`: full research response.
- `agent/StockAgentTools.java`: bounded Spring AI tool methods.
- `agent/StockAnalysisAgent.java`: ChatClient orchestration and structured result.
- `agent/AgentConfiguration.java`, `AgentStatusService.java`: optional model configuration.
- `agent/AgentResearchReport.java`: structured model output.

### Web And Frontend

- `web/StockController.java`, `AgentController.java`, `SystemController.java`: REST endpoints.
- `web/ApiExceptionHandler.java`: RFC 9457 problem details.
- `resources/static/index.html`: dashboard shell.
- `resources/static/styles.css`: approved light-purple design system and responsive grid.
- `resources/static/js/api.js`: REST client and response normalization.
- `resources/static/js/app.js`: search, tabs, loading and partial-failure state.
- `resources/static/js/technical-view.js`: 16+ indicator cards and ECharts drill-down.
- `resources/static/js/views.js`: capital, fundamentals, valuation, events and sources views.
- `resources/prompts/stock-system.st`: agent constraints.
- `resources/prompts/stock-analysis.st`: synthesis template.

### Tests And Documentation

- `src/test/resources/fixtures/**`: sanitized provider fixtures.
- `src/test/java/**`: unit, parser, aggregation, controller and agent tests.
- `src/test/java/**/external/*LiveDataIT.java`: opt-in live verification.
- `scripts/verify-data.ps1`, `scripts/verify-data.sh`: external verification entry points.
- `README.md`: run, learn, configure, test and troubleshoot.
- `AGENTS.md`: repository rules and module ownership.

## Task 1: Reproducible Java And Maven Bootstrap

**Files:**
- Create: `pom.xml`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `scripts/toolchain.properties`
- Create: `scripts/bootstrap-jdk.ps1`
- Create: `scripts/bootstrap-jdk.sh`
- Create: `start.ps1`
- Create: `start.sh`
- Create: `src/main/java/com/astock/agent/AStockAgentApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/com/astock/agent/AStockAgentApplicationTest.java`

- [ ] **Step 1: Write the context smoke test**

```java
@SpringBootTest(properties = "spring.ai.model.chat=none")
class AStockAgentApplicationTest {
    @Test void contextLoadsWithoutModelCredentials() {}
}
```

- [ ] **Step 2: Create the pinned Maven build and verify RED**

Pin Spring Boot `3.5.16`, Spring AI BOM `1.1.8`, Java `21`, ta4j `0.23.0`, ECharts WebJar `6.1.0`, and Lucide WebJar `1.16.0`. Add `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `spring-ai-starter-model-openai`, `caffeine`, `ta4j-core`, the two WebJars, and `spring-boot-starter-test`.

Run: `./mvnw -Dmaven.repo.local=.m2/repository -Dtest=AStockAgentApplicationTest test`

Expected: FAIL because `AStockAgentApplication` and configuration do not exist.

- [ ] **Step 3: Implement the minimal application and optional local config**

```yaml
spring:
  application:
    name: a-stock-agent
  config:
    import: optional:file:./config/application-local.yml
  ai:
    model:
      chat: none
management.endpoints.web.exposure.include: health,info
```

`AStockAgentApplication` contains only `@SpringBootApplication` and `main`.

- [ ] **Step 4: Implement project-local toolchain scripts**

Pin these Temurin 21.0.11+10 artifacts in `toolchain.properties` and refuse any unlisted OS/architecture:

- Windows x64: `https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_x64_windows_hotspot_21.0.11_10.zip`, SHA-256 `d3625e7cadf23787ea540229544b6e2ab494b3b54da1801879e583e1dfee0a64`.
- Linux x64: `https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.11_10.tar.gz`, SHA-256 `4b2220e232a97997b436ca6ab15cbf70171ecff52958a46159dfa5a8c44ca4de`.
- macOS x64: `https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_x64_mac_hotspot_21.0.11_10.tar.gz`, SHA-256 `34180eb03e6d207c388cce3da668f6cc7cd7508c185c24782fadac2c9c0e66f9`.
- macOS arm64: `https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.11_10.tar.gz`, SHA-256 `6ebcf221c9b41507b14c098e93c6ead6440b8d9bd154f8ec666c4c73abbdb201`.

Both scripts verify the selected checksum before extraction.

`start.ps1` sets `JAVA_HOME=.tools/jdk-21`, `MAVEN_USER_HOME=.m2`, then invokes:

```powershell
& .\mvnw.cmd '-Dmaven.repo.local=.m2/repository' spring-boot:run
```

- [ ] **Step 5: Verify GREEN and wrapper isolation**

Run: `./mvnw -Dmaven.repo.local=.m2/repository -Dtest=AStockAgentApplicationTest test`

Expected: `Tests run: 1, Failures: 0, Errors: 0`.

Run: `./mvnw -v`

Expected: Maven `3.9.11` and Java `21.0.11` when invoked through the bootstrap script.

- [ ] **Step 6: Commit**

```bash
git add pom.xml .mvn mvnw mvnw.cmd scripts start.ps1 start.sh src/main src/test
git commit -m "build: add isolated Java and Maven runtime"
```

## Task 2: Normalized Security And Provenance Domain

**Files:**
- Create: `src/main/java/com/astock/agent/marketdata/model/Exchange.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/SecurityId.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/SectionStatus.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/Provenance.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/DataSection.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/Quote.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/DailyBar.java`
- Test: `src/test/java/com/astock/agent/marketdata/model/SecurityIdTest.java`
- Test: `src/test/java/com/astock/agent/marketdata/model/DataSectionTest.java`

- [ ] **Step 1: Write failing normalization tests**

```java
@ParameterizedTest
@CsvSource({"600519,SHANGHAI,sh600519", "000001,SHENZHEN,sz000001", "300750,SHENZHEN,sz300750", "830799,BEIJING,bj830799"})
void normalizesMarketPrefixes(String code, Exchange exchange, String vendorCode) {
    SecurityId id = SecurityId.parse(code);
    assertThat(id.exchange()).isEqualTo(exchange);
    assertThat(id.tencentCode()).isEqualTo(vendorCode);
}

@ParameterizedTest
@ValueSource(strings = {"", "12345", "ABC519", "700001"})
void rejectsUnsupportedCodes(String code) {
    assertThatThrownBy(() -> SecurityId.parse(code)).isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dmaven.repo.local=.m2/repository -Dtest=SecurityIdTest,DataSectionTest test`

Expected: FAIL with missing domain types.

- [ ] **Step 3: Implement immutable records and invariants**

`SecurityId.parse` accepts exactly six digits and routes `6/68` to Shanghai, `0/2/3` to Shenzhen, and `4/8/92` to Beijing. `DataSection<T>` exposes `healthy`, `degraded`, `stale`, `unverified`, and `unavailable` factories and forbids `HEALTHY` without payload and provenance.

- [ ] **Step 4: Verify GREEN**

Run the same test command. Expected: all domain tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/astock/agent/marketdata/model src/test/java/com/astock/agent/marketdata/model
git commit -m "feat: add normalized market data domain"
```

## Task 3: Provider HTTP Guardrails And Health

**Files:**
- Create: `src/main/java/com/astock/agent/config/MarketDataProperties.java`
- Create: `src/main/java/com/astock/agent/marketdata/provider/ProviderId.java`
- Create: `src/main/java/com/astock/agent/marketdata/provider/ProviderException.java`
- Create: `src/main/java/com/astock/agent/marketdata/provider/ProviderHttpClient.java`
- Create: `src/main/java/com/astock/agent/marketdata/provider/ProviderThrottle.java`
- Create: `src/main/java/com/astock/agent/marketdata/provider/ProviderHealthRegistry.java`
- Test: `src/test/java/com/astock/agent/marketdata/provider/ProviderThrottleTest.java`
- Test: `src/test/java/com/astock/agent/marketdata/provider/ProviderHealthRegistryTest.java`

- [ ] **Step 1: Write failing protection tests**

```java
@Test void eastmoneyRequestsNeverOverlap() throws Exception {
    ProviderThrottle throttle = new ProviderThrottle(Duration.ofMillis(20), Duration.ZERO);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger max = new AtomicInteger();
    runConcurrently(4, () -> throttle.call(ProviderId.EASTMONEY, () -> {
        max.accumulateAndGet(active.incrementAndGet(), Math::max);
        Thread.sleep(10);
        active.decrementAndGet();
        return true;
    }));
    assertThat(max).hasValue(1);
}

@Test void http403StartsCooldownWithoutRetry() {
    registry.recordBlocked(ProviderId.EASTMONEY, Instant.parse("2026-07-15T02:00:00Z"));
    assertThat(registry.availability(ProviderId.EASTMONEY).status()).isEqualTo(COOLDOWN);
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dmaven.repo.local=.m2/repository -Dtest=ProviderThrottleTest,ProviderHealthRegistryTest test`

Expected: FAIL with missing provider infrastructure.

- [ ] **Step 3: Implement bounded transport behavior**

Use JDK `HttpClient` with a 3-second connect timeout and per-request 8-second timeout. Retry connection errors, `429`, and `5xx` at most twice with bounded exponential backoff. Never retry `403`. Redact query values whose names contain `key`, `token`, `secret`, or `authorization` from logs.

- [ ] **Step 4: Verify GREEN**

Run the same provider tests. Expected: all pass and concurrency maximum equals one for Eastmoney.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/astock/agent/config src/main/java/com/astock/agent/marketdata/provider src/test/java/com/astock/agent/marketdata/provider
git commit -m "feat: guard external market data requests"
```

## Task 4: Quote And K-Line Vertical Slice

**Files:**
- Create: `src/main/java/com/astock/agent/marketdata/provider/tencent/TencentMarketDataClient.java`
- Create: `src/main/java/com/astock/agent/marketdata/provider/tencent/TencentResponseParser.java`
- Create: `src/main/java/com/astock/agent/marketdata/provider/baidu/BaiduKlineClient.java`
- Create: `src/main/java/com/astock/agent/marketdata/validation/MarketDataValidator.java`
- Create: `src/test/resources/fixtures/tencent/quote-600519.txt`
- Create: `src/test/resources/fixtures/tencent/kline-600519.json`
- Create: `src/test/resources/fixtures/baidu/kline-600519.json`
- Test: `src/test/java/com/astock/agent/marketdata/provider/tencent/TencentMarketDataClientTest.java`
- Test: `src/test/java/com/astock/agent/marketdata/provider/baidu/BaiduKlineClientTest.java`
- Test: `src/test/java/com/astock/agent/marketdata/validation/MarketDataValidatorTest.java`

- [ ] **Step 1: Capture sanitized fixtures through explicit live commands**

Use the `a-stock-data` documented Tencent quote and K-line routes plus Baidu Stock K-line route for `600519`. Store only public response bodies, encoded as UTF-8, with no request headers containing local identifiers.

- [ ] **Step 2: Write failing parser and validation tests**

```java
@Test void parsesTencentQuoteUnitsAndIdentity() {
    Quote quote = parser.parseQuote(fixture("tencent/quote-600519.txt"), SecurityId.parse("600519"));
    assertThat(quote.name()).isEqualTo("贵州茅台");
    assertThat(quote.price()).isPositive();
    assertThat(quote.previousClose()).isPositive();
    assertThat(quote.marketValueYuan()).isPositive();
}

@Test void rejectsImpossibleOhlc() {
    DailyBar invalid = bar("2026-07-14", 10, 12, 9, 11);
    assertThat(validator.validateBars(List.of(invalid))).anyMatch(i -> i.code().equals("OHLC_RANGE"));
}
```

- [ ] **Step 3: Run RED**

Run: `./mvnw -Dmaven.repo.local=.m2/repository -Dtest='*Tencent*,*Baidu*,MarketDataValidatorTest' test`

Expected: FAIL because parsers and validator are missing.

- [ ] **Step 4: Implement quote, adjusted bars and cross-check**

Decode Tencent quote responses as GBK when the content type does not declare UTF-8. Normalize all amounts to yuan and retain raw values in parser diagnostics. Sort bars ascending, remove exact duplicate dates, reject conflicting duplicates, and require at least 260 valid bars for a professional snapshot.

- [ ] **Step 5: Verify GREEN**

Run the same command. Expected: parser, unit, OHLC and identity tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/astock/agent/marketdata/provider/{tencent,baidu} src/main/java/com/astock/agent/marketdata/validation src/test
git commit -m "feat: retrieve and validate quotes and k-lines"
```

## Task 5: Research, Capital, Fundamental And Event Providers

**Files:**
- Create: `src/main/java/com/astock/agent/marketdata/model/CompanyProfile.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/FundFlow.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/CapitalData.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/FundamentalData.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/ResearchItem.java`
- Create: `src/main/java/com/astock/agent/marketdata/model/EventData.java`
- Create: `src/main/java/com/astock/agent/marketdata/provider/eastmoney/EastmoneyResearchClient.java`
- Create: `src/main/java/com/astock/agent/marketdata/provider/eastmoney/EastmoneyDataCenterQuery.java`
- Create: `src/main/java/com/astock/agent/marketdata/provider/sina/SinaFinanceClient.java`
- Create: `src/main/java/com/astock/agent/marketdata/provider/cninfo/CninfoAnnouncementClient.java`
- Create: `src/test/resources/fixtures/eastmoney/sectors-600519.json`
- Create: `src/test/resources/fixtures/eastmoney/fund-flow-600519.json`
- Create: `src/test/resources/fixtures/eastmoney/reports-600519.json`
- Create: `src/test/resources/fixtures/eastmoney/news-600519.json`
- Create: `src/test/resources/fixtures/eastmoney/capital-events-600519.json`
- Create: `src/test/resources/fixtures/sina/statements-600519.json`
- Create: `src/test/resources/fixtures/sina/fund-flow-600519.json`
- Create: `src/test/resources/fixtures/cninfo/announcements-600519.json`
- Test: `src/test/java/com/astock/agent/marketdata/provider/ExtendedProviderContractTest.java`

- [ ] **Step 1: Write one failing contract test per capability**

```java
@TestFactory Stream<DynamicTest> providerContracts() {
    return Stream.of(
        contract("sector", eastmoney::parseSectors, payload -> assertThat(payload).isNotEmpty()),
        contract("fund-flow", eastmoney::parseFundFlow, payload -> assertThat(payload).isNotEmpty()),
        contract("reports", eastmoney::parseReports, payload -> assertThat(payload).allMatch(r -> r.publishedAt() != null)),
        contract("statements", sina::parseStatements, payload -> assertThat(payload.reportPeriod()).isNotNull()),
        contract("announcements", cninfo::parseAnnouncements, payload -> assertThat(payload).allMatch(a -> a.url().startsWith("http")))
    ).map(Contract::asDynamicTest);
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dmaven.repo.local=.m2/repository -Dtest=ExtendedProviderContractTest test`

Expected: FAIL with missing models and clients.

- [ ] **Step 3: Implement exact provider queries**

Implement the skill-documented routes for sectors, minute/120-day fund flow, reports, news, margin financing, block trades, shareholder count, unlocks, dividends, and dragon-tiger records. Every Eastmoney call goes through `ProviderThrottle`; `403` returns a degraded section. Implement Sina statements and daily fund-flow fallback, plus CNInfo announcement org-id lookup and search.

- [ ] **Step 4: Verify GREEN and empty-vs-error semantics**

Add assertions that a successful empty response yields `HEALTHY` with an empty list while invalid JSON yields `UNAVAILABLE` with a parse issue.

Run the same test command. Expected: all capability contracts pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/astock/agent/marketdata src/test/java/com/astock/agent/marketdata src/test/resources/fixtures
git commit -m "feat: add multi-source research data providers"
```

## Task 6: Professional Technical Analysis Engine

**Files:**
- Create: `src/main/java/com/astock/agent/technical/Timeframe.java`
- Create: `src/main/java/com/astock/agent/technical/IndicatorState.java`
- Create: `src/main/java/com/astock/agent/technical/IndicatorCard.java`
- Create: `src/main/java/com/astock/agent/technical/TechnicalSnapshot.java`
- Create: `src/main/java/com/astock/agent/technical/BarSeriesFactory.java`
- Create: `src/main/java/com/astock/agent/technical/CustomIndicators.java`
- Create: `src/main/java/com/astock/agent/technical/TechnicalAnalysisService.java`
- Test: `src/test/java/com/astock/agent/technical/BarSeriesFactoryTest.java`
- Test: `src/test/java/com/astock/agent/technical/TechnicalAnalysisServiceTest.java`
- Test: `src/test/java/com/astock/agent/technical/CustomIndicatorsTest.java`

- [ ] **Step 1: Write failing deterministic indicator tests**

Use a fixed 300-bar generated series and independently calculated expected values.

```java
@Test void calculatesCanonicalRsiAndMacd() {
    TechnicalSnapshot result = service.analyze(series, Timeframe.DAILY);
    assertThat(result.card("RSI_12").value()).isCloseTo(56.214, within(0.001));
    assertThat(result.card("MACD_12_26_9").parameters()).containsEntry("fast", 12);
}

@Test void weeklyAggregationUsesLastCloseAndSummedVolume() {
    List<DailyBar> weekly = factory.aggregate(dailyBars, Timeframe.WEEKLY);
    assertThat(weekly.getFirst().close()).isEqualByComparingTo(fridayClose);
    assertThat(weekly.getFirst().volume()).isEqualByComparingTo(sumOfWeekVolume);
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dmaven.repo.local=.m2/repository -Dtest='*Technical*,BarSeriesFactoryTest' test`

Expected: FAIL because the engine is absent.

- [ ] **Step 3: Implement ta4j-backed indicators**

Implement all parameters from the approved design: SMA/EMA, BIAS, ADX/DMI, Aroon, TRIX, Ichimoku, Supertrend, Donchian, MACD, RSI, Stochastic RSI, KDJ, CCI, ROC, Williams %R, PSY, Bollinger, ATR/NATR, OBV, MFI, CMF and A/D. Use `CustomIndicators` only for PSY, anchored Fibonacci levels, historical VaR/CVaR, skewness, kurtosis and any ta4j gap.

- [ ] **Step 4: Implement explainable states**

Each `IndicatorCard` must contain `id`, `group`, `name`, `parameters`, `value`, `unit`, `state`, `trigger`, `series`, `calculatedAt`, and `sectionStatus`. No state may be created without a trigger string that cites the comparison.

- [ ] **Step 5: Verify GREEN and formula boundaries**

Run the same tests. Expected: fixed-value assertions pass for daily, weekly and monthly series, including zero-volume and insufficient-history cases.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/astock/agent/technical src/test/java/com/astock/agent/technical
git commit -m "feat: calculate explainable technical indicators"
```

## Task 7: Data Quality And Partial Research Snapshot

**Files:**
- Create: `src/main/java/com/astock/agent/analysis/DataQualityBreakdown.java`
- Create: `src/main/java/com/astock/agent/analysis/DataQualityScorer.java`
- Create: `src/main/java/com/astock/agent/analysis/StockResearchSnapshot.java`
- Create: `src/main/java/com/astock/agent/analysis/ResearchAggregationService.java`
- Create: `src/main/java/com/astock/agent/config/CacheConfiguration.java`
- Test: `src/test/java/com/astock/agent/analysis/DataQualityScorerTest.java`
- Test: `src/test/java/com/astock/agent/analysis/ResearchAggregationServiceTest.java`

- [ ] **Step 1: Write failing score and partial-failure tests**

```java
@Test void scoreIsTransparentSumOfApprovedWeights() {
    DataQualityBreakdown score = scorer.score(freshConsistentCompleteSnapshot());
    assertThat(score.freshness()).isEqualTo(30);
    assertThat(score.consistency()).isEqualTo(30);
    assertThat(score.completeness()).isEqualTo(25);
    assertThat(score.authority()).isEqualTo(15);
    assertThat(score.total()).isEqualTo(100);
}

@Test void newsFailureDoesNotDiscardQuoteOrTechnicalData() {
    when(eastmoney.news(any())).thenThrow(new ProviderException("blocked"));
    StockResearchSnapshot result = service.research(SecurityId.parse("600519"));
    assertThat(result.quote().status()).isEqualTo(HEALTHY);
    assertThat(result.technical().status()).isEqualTo(HEALTHY);
    assertThat(result.news().status()).isEqualTo(UNAVAILABLE);
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dmaven.repo.local=.m2/repository -Dtest='*Quality*,ResearchAggregationServiceTest' test`

Expected: FAIL with missing aggregation types.

- [ ] **Step 3: Implement concurrent-safe aggregation and TTL caches**

Use virtual threads for independent non-Eastmoney calls and let the provider throttle serialize Eastmoney calls. Configure the approved quote, bars, fund-flow, research, announcement, statement and profile TTLs. Cache keys include security, data type, adjustment, and timeframe.

- [ ] **Step 4: Verify GREEN**

Run the same tests. Expected: score totals and partial states match exactly; a complete core failure returns the explicit aggregate exception.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/astock/agent/analysis src/main/java/com/astock/agent/config/CacheConfiguration.java src/test/java/com/astock/agent/analysis
git commit -m "feat: aggregate partial stock research snapshots"
```

## Task 8: Optional Spring AI Agent With Bounded Tools

**Files:**
- Create: `config/application-local.yml.example`
- Create: `src/main/java/com/astock/agent/agent/AgentAvailability.java`
- Create: `src/main/java/com/astock/agent/agent/AgentStatusService.java`
- Create: `src/main/java/com/astock/agent/agent/AgentResearchReport.java`
- Create: `src/main/java/com/astock/agent/agent/StockAgentTools.java`
- Create: `src/main/java/com/astock/agent/agent/StockAnalysisAgent.java`
- Create: `src/main/java/com/astock/agent/agent/AgentConfiguration.java`
- Create: `src/main/resources/prompts/stock-system.st`
- Create: `src/main/resources/prompts/stock-analysis.st`
- Test: `src/test/java/com/astock/agent/agent/AgentStatusServiceTest.java`
- Test: `src/test/java/com/astock/agent/agent/StockAgentToolsTest.java`
- Test: `src/test/java/com/astock/agent/agent/StockAnalysisAgentTest.java`

- [ ] **Step 1: Write failing no-secret and tool-boundary tests**

```java
@Test void reportsDisabledWhenKeyIsMissing() {
    assertThat(statusService.status()).isEqualTo(DISABLED_CONFIGURATION_MISSING);
}

@Test void toolRejectsUnnormalizedSymbol() {
    assertThatThrownBy(() -> tools.getResearchSnapshot("贵州茅台<script>"))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test void synthesisPreservesMissingSections() {
    AgentResearchReport report = agent.analyze(snapshotWithUnavailableNews());
    assertThat(report.missingData()).contains("news");
    assertThat(report.sources()).allMatch(SourceCitation::hasFetchTime);
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dmaven.repo.local=.m2/repository -Dtest='*Agent*,StockAgentToolsTest' test`

Expected: FAIL with missing agent types.

- [ ] **Step 3: Implement conditional ChatClient and tool schemas**

Create the model beans only when `spring.ai.openai.api-key` is nonblank and chat model is not `none`. Expose the six approved tools using Spring AI `@Tool`. The tool methods delegate to application services and never accept arbitrary URLs.

- [ ] **Step 4: Implement structured output and prompts**

The system prompt requires source citations, data times, conflicts, missing sections, and the Chinese disclaimer `仅供学习研究，不构成投资建议`. Temperature remains `0.2`. Parse output to `AgentResearchReport`; a structured-output parse failure becomes a typed agent error and never returns raw HTML.

- [ ] **Step 5: Verify GREEN**

Use Spring AI's test model or a deterministic stub `ChatModel`, not a live API. Run the same tests and expect all pass without a secret file.

- [ ] **Step 6: Commit**

```bash
git add config src/main/java/com/astock/agent/agent src/main/resources/prompts src/test/java/com/astock/agent/agent
git commit -m "feat: add optional bounded Spring AI agent"
```

## Task 9: REST API And Problem Details

**Files:**
- Create: `src/main/java/com/astock/agent/web/StockController.java`
- Create: `src/main/java/com/astock/agent/web/AgentController.java`
- Create: `src/main/java/com/astock/agent/web/SystemController.java`
- Create: `src/main/java/com/astock/agent/web/ApiExceptionHandler.java`
- Test: `src/test/java/com/astock/agent/web/StockControllerTest.java`
- Test: `src/test/java/com/astock/agent/web/AgentControllerTest.java`
- Test: `src/test/java/com/astock/agent/web/SystemControllerTest.java`

- [ ] **Step 1: Write failing MockMvc contracts**

```java
@Test void snapshotReturns200ForPartialSuccess() throws Exception {
    mvc.perform(get("/api/stocks/600519/snapshot"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.quote.status").value("HEALTHY"))
       .andExpect(jsonPath("$.news.status").value("UNAVAILABLE"));
}

@Test void invalidCodeUsesProblemDetails() throws Exception {
    mvc.perform(get("/api/stocks/ABC/snapshot"))
       .andExpect(status().isBadRequest())
       .andExpect(content().contentType("application/problem+json"))
       .andExpect(jsonPath("$.code").value("INVALID_SECURITY_CODE"));
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dmaven.repo.local=.m2/repository -Dtest='*ControllerTest' test`

Expected: 404 or missing controller failures.

- [ ] **Step 3: Implement all approved endpoints**

Implement search, snapshot, technical, sources, agent analyze/chat/status, and data-source health. Validate query lengths and codes. Limit chat messages to 2,000 Unicode code points and retain at most 12 turns per in-memory conversation.

- [ ] **Step 4: Verify GREEN**

Run the same command. Expected: controller tests pass with stable JSON and problem-details fields.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/astock/agent/web src/test/java/com/astock/agent/web
git commit -m "feat: expose stock research and agent APIs"
```

## Task 10: Approved Light-Purple Dashboard Shell

**Files:**
- Create: `src/main/resources/static/index.html`
- Create: `src/main/resources/static/styles.css`
- Create: `src/main/resources/static/js/api.js`
- Create: `src/main/resources/static/js/app.js`
- Create: `src/main/resources/static/js/views.js`
- Test: `src/test/java/com/astock/agent/web/StaticResourceTest.java`

- [ ] **Step 1: Write a failing static-resource contract**

```java
@Test void servesActualResearchWorkbenchAtRoot() throws Exception {
    mvc.perform(get("/"))
       .andExpect(status().isOk())
       .andExpect(content().string(containsString("A 股智能研究台")))
       .andExpect(content().string(containsString("data-view=\"technical\"")));
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dmaven.repo.local=.m2/repository -Dtest=StaticResourceTest test`

Expected: 404 or missing content.

- [ ] **Step 3: Implement accessible page structure and tokens**

Use the approved tokens `#f7f7f4`, `#fafaf7`, `#ffffff`, `#26251e`, `#5a5852`, `#807d72`, `#e6e5e0`, `#cfcdc4`, `#7158d9`, `#eeeafd`, `#d83b53`, and `#1f8a65`. Build a compact header, search combobox, quote strip, seven tabs, main content, status region, and disclaimer. Use Lucide icons through the WebJar and label icon-only actions with `aria-label` and tooltips.

- [ ] **Step 4: Implement UI states**

`app.js` owns `idle`, `loading`, `partial`, `success`, and `error` states. Disable the search command while loading, preserve stable dimensions with skeletons, announce completion through `aria-live`, and display section errors next to their owning view.

- [ ] **Step 5: Verify GREEN**

Run the same test command. Expected: root resource contract passes.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static src/test/java/com/astock/agent/web/StaticResourceTest.java
git commit -m "feat: build light-purple stock research dashboard"
```

## Task 11: Multi-Card Technical And Research Views

**Files:**
- Create: `src/main/resources/static/js/technical-view.js`
- Modify: `src/main/resources/static/js/views.js`
- Modify: `src/main/resources/static/styles.css`
- Test: `tests/ui/dashboard.spec.js`
- Test: `tests/ui/fixtures/partial-snapshot.json`

- [ ] **Step 1: Write failing Playwright journeys**

```javascript
test('technical indicators fill a four-column desktop grid', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('tab', { name: '技术分析' }).click();
  await expect(page.locator('.indicator-card')).toHaveCount(16);
  const columns = await page.locator('.indicator-grid').evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').length);
  expect(columns).toBe(4);
});

test('mobile collapses cards without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
```

- [ ] **Step 2: Run RED**

Run: `playwright-cli test tests/ui/dashboard.spec.js`

Expected: FAIL because indicator rendering and fixtures do not exist.

- [ ] **Step 3: Implement technical cards and chart drill-down**

Render grouped cards for trend, momentum, volatility/volume, and relative-strength/risk. Every card shows name, parameters, current value, state label, mini-series, trigger, data date, and source status. Desktop uses four columns, intermediate widths three/two, and mobile one. Selecting a card updates one unframed ECharts detail region; it does not create a card inside another card.

- [ ] **Step 4: Implement the remaining views**

Render capital/chips, fundamentals, valuation/expectations, events/news and source-quality views. Tables have sticky headers only when their scroll container has a fixed height. Empty, unavailable and stale sections use text plus icons, not color alone.

- [ ] **Step 5: Verify GREEN with visual and canvas checks**

Run Playwright at 1440x1000, 1024x768, 768x1024 and 390x844. Assert ECharts canvas has nontransparent pixels, no element overlaps the fixed header, all buttons fit their text, and the page has no horizontal overflow.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static tests/ui
git commit -m "feat: render professional multi-card analysis views"
```

## Task 12: Live Data Verification And Documentation

**Files:**
- Create: `src/test/java/com/astock/agent/external/LiveDataIT.java`
- Create: `scripts/verify-data.ps1`
- Create: `scripts/verify-data.sh`
- Create: `README.md`
- Create: `AGENTS.md`
- Create: `docs/architecture/data-flow.md`
- Create: `docs/data-sources/provider-matrix.md`
- Modify: `pom.xml`

- [ ] **Step 1: Write the opt-in external test**

```java
@Tag("external")
@ParameterizedTest
@ValueSource(strings = {"600519", "000001", "300750"})
void liveSourcesReturnSaneRecentData(String code) {
    StockResearchSnapshot snapshot = service.research(SecurityId.parse(code));
    assertThat(snapshot.quote().payload()).isPresent();
    assertThat(snapshot.bars().payload()).get().asList().hasSizeGreaterThanOrEqualTo(260);
    assertThat(snapshot.validationIssues()).noneMatch(i -> i.severity() == FATAL);
}
```

- [ ] **Step 2: Verify default tests exclude network**

Run: `./mvnw -Dmaven.repo.local=.m2/repository test`

Expected: external tests are skipped and no public provider is contacted.

- [ ] **Step 3: Implement explicit verification scripts**

Scripts invoke the Maven `external` profile and save a timestamped JSON and Markdown report under `target/data-verification/`. The report lists security, provider, source time, fetch time, status, fallback, validation issues and cross-source differences.

- [ ] **Step 4: Write README and AGENTS**

README must include one-command Windows/Unix startup, compatible-model configuration, architecture, agent learning path, data sources, quality score, UI screenshots, test commands, external verification, provider-block troubleshooting and investment disclaimer.

AGENTS must require provider throttling, provenance preservation, red-green-refactor for behavior, no committed local config, no direct trade recommendation, A-share red-up/green-down semantics, approved UI tokens, and fresh verification before completion.

- [ ] **Step 5: Run live verification**

Run: `./scripts/verify-data.ps1`

Expected: a report is generated. Individual optional providers may be `DEGRADED` or `UNAVAILABLE`, but quote identity, at least 260 bars and OHLC validity must pass for all three securities.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/test/java/com/astock/agent/external scripts README.md AGENTS.md docs
git commit -m "docs: add verification and contributor guide"
```

## Task 13: Full Completion Verification

**Files:**
- Modify only files implicated by failing verification.

- [ ] **Step 1: Run all offline tests from a clean build**

Run: `./mvnw -Dmaven.repo.local=.m2/repository clean test`

Expected: zero failures, zero errors, and external tests excluded.

- [ ] **Step 2: Build the executable JAR**

Run: `./mvnw -Dmaven.repo.local=.m2/repository -DskipTests package`

Expected: `target/a-stock-agent-*.jar` and exit code 0.

- [ ] **Step 3: Start without model credentials and exercise APIs**

Run the JAR with no `config/application-local.yml`. Verify `/actuator/health`, `/api/agent/status`, `/api/stocks/600519/snapshot`, `/api/stocks/600519/technical`, and `/`.

Expected: application health `UP`, agent status `DISABLED_CONFIGURATION_MISSING`, real or explicitly degraded stock sections, and HTTP 200 dashboard.

- [ ] **Step 4: Run external and UI verification**

Run `scripts/verify-data.ps1` and the Playwright desktop/mobile suite. Review screenshots and JSON reports rather than trusting exit codes alone.

- [ ] **Step 5: Audit requirements and secrets**

Check every acceptance criterion in the design spec. Run:

```bash
git grep -n -E '(api[-_]?key|secret|token)[[:space:]]*[:=][[:space:]]*[^${<]' -- ':!config/application-local.yml.example'
git status --short
```

Expected: no committed credential values and only intentional working-tree changes.

- [ ] **Step 6: Commit final corrections**

```bash
git add -A
git commit -m "chore: complete end-to-end verification"
```
