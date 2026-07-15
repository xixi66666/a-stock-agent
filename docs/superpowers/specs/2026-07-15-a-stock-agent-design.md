# A-Stock Spring AI Agent Design

**Date:** 2026-07-15
**Status:** Approved in conversation; awaiting review of this written specification

## 1. Purpose

Build a Java learning project that demonstrates a complete Spring AI agent workflow around real A-share stock research. A user enters or selects a stock, the application gathers public data from several independent sources, validates and derives technical indicators, displays the evidence in a professional dashboard, and optionally asks a Spring AI agent to synthesize the verified data.

The first release is a single-stock research workbench. It prioritizes inspectable data, explicit provenance, graceful partial failure, and code that teaches agent concepts over automated trading or prediction.

## 2. Goals

- Run as a conventional Spring Boot Java project without Docker.
- Isolate the supported JDK, Maven distribution, Maven repository, and runtime caches inside the repository working directory.
- Use Spring AI `ChatClient`, tools, advisors, prompt templates, and structured output in a real agent flow.
- Accept any OpenAI-compatible model endpoint through a Git-ignored local configuration file.
- Continue to serve the complete non-AI stock dashboard when no model API key is configured.
- Implement the public data-source capabilities selected from the `a-stock-data` skill as native Java clients.
- Show broad market, technical, capital, fundamental, valuation, research, news, and event dimensions.
- Make data freshness, source, validation status, fallback use, and errors visible to the user.
- Provide deterministic offline tests and separately tagged live-data verification tests.
- Ship a responsive, light, purple-accented research UI based on the approved `python-a` reference and the Linear design tokens selected through `awesome-design-md`.

## 3. Non-Goals

- No order placement, brokerage integration, portfolio accounting, or automated trading.
- No recommendation phrased as a direct buy or sell instruction.
- No batch screening of the whole market in the first release.
- No minute-level technical analysis; the first release derives daily, weekly, and monthly views from daily bars.
- No database or user account system. Caches are process-local and disposable.
- No runtime dependency on Codex skills. Skills are design and implementation references; cloned users receive a self-contained Java application.
- No Docker, Node.js build chain, Python sidecar, or locally downloaded LLM model.

## 4. Runtime And Repository Layout

The project root is the current `a-stock-agent` workspace directory.

```text
.
|-- .mvn/wrapper/                     Maven Wrapper metadata
|-- config/application-local.yml.example
|-- docs/
|   |-- architecture/
|   |-- data-sources/
|   `-- superpowers/
|-- scripts/
|   |-- bootstrap-jdk.ps1
|   |-- bootstrap-jdk.sh
|   |-- verify-data.ps1
|   `-- verify-data.sh
|-- src/main/java/com/astock/agent/
|   |-- agent/                        Spring AI tools and orchestration
|   |-- analysis/                     Aggregation and signal explanations
|   |-- marketdata/                   Provider clients and provenance
|   |-- technical/                    Bar series and technical calculations
|   |-- web/                          REST API and error mapping
|   `-- AStockAgentApplication.java
|-- src/main/resources/
|   |-- static/                       HTML, CSS, JavaScript, local UI assets
|   |-- prompts/                      Versioned system and analysis prompts
|   `-- application.yml
|-- src/test/java/com/astock/agent/
|-- mvnw
|-- mvnw.cmd
|-- start.ps1
|-- start.sh
|-- pom.xml
|-- AGENTS.md
`-- README.md
```

Java 21 is the supported runtime. `start.ps1` and `start.sh` first locate a compatible local Java runtime. If one is absent, they download a pinned Eclipse Temurin JDK 21 archive into `.tools/jdk-21`, verify its SHA-256 checksum, set `JAVA_HOME` for the child process, and invoke Maven Wrapper. Maven Wrapper and the local repository use `.m2/` in the project rather than a machine-wide Maven installation.

The Spring dependency line will remain within the compatible Spring Boot 3.5.x and Spring AI 1.1.x families. Exact patch versions are pinned in `pom.xml` after resolving them from Maven Central during implementation; no dynamic version ranges are allowed.

## 5. Application Architecture

The application is a modular monolith with five explicit boundaries.

### 5.1 Market Data

Each provider adapter owns URL construction, headers, response decoding, field mapping, source timestamps, and provider-specific error classification. Adapters return typed domain records plus provenance instead of exposing provider JSON to the rest of the application.

Shared infrastructure provides:

- six-digit security-code normalization and Shanghai/Shenzhen/Beijing market routing;
- one reusable HTTP client per provider;
- explicit connect and read timeouts;
- bounded retry only for connection failures, HTTP 429, and HTTP 5xx;
- circuit breaking and concurrency limits;
- Eastmoney-specific serialized rate limiting with jitter;
- short-lived Caffeine caches by data type;
- structured request diagnostics without secrets or full response bodies.

### 5.2 Technical Analysis

The technical module accepts a normalized, front-adjusted daily bar series. It uses `ta4j` where the library has a canonical implementation. Indicators not available in `ta4j` are small, separately tested calculators with documented formulas and parameters.

It derives weekly and monthly series from the same daily source so that all timeframes share a consistent adjustment basis.

### 5.3 Research Aggregation

The aggregation service runs independent provider calls concurrently where provider policies permit it, serializes Eastmoney calls, and returns a partial `StockResearchSnapshot`. A failed section never discards successful sections.

Every section contains:

- section status;
- normalized payload;
- source name and source URL;
- provider timestamp and application fetch timestamp;
- cache status;
- validation messages;
- fallback source, when used.

### 5.4 Spring AI Agent

The agent consumes only normalized and validated application data. It does not receive an unrestricted URL fetch tool.

Spring AI capabilities demonstrated by the project:

- `ChatClient` with a versioned system prompt;
- advisor chain for request context, observation, and conversation memory;
- Java methods exposed as model tools;
- structured output for the final research synthesis;
- tool-call traces that can be inspected in development mode;
- model-disabled behavior when configuration is absent;
- citations pointing back to source metadata in the snapshot.

### 5.5 Web Application

Spring MVC serves REST endpoints and a static single-page dashboard from the same JAR. The frontend uses framework-free JavaScript, ECharts from a Maven WebJar, and Lucide icons from a Maven WebJar. There is no Node.js build step.

## 6. Data Sources

The first release implements native Java equivalents of the capabilities chosen from `a-stock-data`.

| Domain | Primary | Fallback / Cross-check | Notes |
|---|---|---|---|
| Symbol search and quote | Tencent Finance | latest completed K-line close | Stable HTTP source for price, PE, PB, market value, turnover, limits, indexes, and ETFs |
| Adjusted daily K-line | Tencent Finance | Baidu Stock | Fetch approximately 500 trading days; Baidu also supplies independent moving-average fields |
| Industry and concepts | Eastmoney `slist` | section unavailable | Eastmoney requests are globally serialized and rate-limited |
| Intraday and historical fund flow | Eastmoney `push2/push2his` | Sina daily fund flow | Unit conversion is explicit; raw units remain available in diagnostics |
| Research reports | Eastmoney `reportapi` | section unavailable | Metadata only in the first release; PDF downloading is not automatic |
| Company news | Eastmoney search API | global news section omitted | Results include publication time and canonical URL |
| Financial statements | Sina Finance | Tencent quote fundamentals | Income statement, balance sheet, and cash-flow statement |
| Announcements | CNInfo | exchange/Eastmoney fallback where available | Preserve announcement date, type, and PDF URL |
| Margin financing | Eastmoney data center | section unavailable | Latest and recent history |
| Block trades | Eastmoney data center | section unavailable | Recent trades only |
| Dragon-tiger list | Eastmoney data center | exchange official endpoints | Show institution and seat details when present |
| Shareholder count | Eastmoney data center | section unavailable | Include report date and change percentage |
| Restricted-share unlocks | Eastmoney data center | section unavailable | Historical and next 90 days |
| Dividends | Eastmoney data center | company announcements | Show plan status and ex-date |
| Benchmark quote and bars | Tencent Finance | Baidu Stock |沪深 300 is the default relative-strength benchmark |

No iwencai key is required in the first release. Semantic report search is deferred because it adds a second secret and is not needed for single-stock research.

## 7. Caching And Provider Protection

Default cache policies:

| Data | Trading Hours TTL | Off-hours TTL |
|---|---:|---:|
| Quote | 15 seconds | 5 minutes |
| Daily bars | 15 minutes | 60 minutes |
| Fund flow | 5 minutes | 30 minutes |
| News and reports | 15 minutes | 30 minutes |
| Announcements | 30 minutes | 2 hours |
| Financial statements | 12 hours | 12 hours |
| Company profile, sectors | 24 hours | 24 hours |

Eastmoney calls share one application-wide limiter. Requests are serialized with a minimum interval and randomized jitter, never issued in a fan-out loop. HTTP 403 is treated as a provider-block signal and is not retried. The UI then displays the fallback or an unavailable section.

## 8. Data Quality And Truthfulness

The UI shows a transparent **data quality score**, not a claim that a number is guaranteed true.

The 0-100 score is composed of:

- freshness: 30 points;
- cross-source consistency: 30 points;
- required-field completeness: 25 points;
- source authority and successful fallback state: 15 points.

The breakdown is visible in the data-sources view. A score is never shown without its component statuses.

Validation rules include:

- returned security code, name, and market must match the request;
- prices and volumes cannot be negative;
- `low <= open/close <= high` for every bar;
- bar dates must be strictly increasing and unique;
- timestamps cannot be materially in the future;
- valuation ratios and percentages must remain within documented sanity bounds;
- Tencent previous close must match the latest completed cross-check bar within the larger of one tick or 0.5%;
- after market close, the Tencent latest price and completed daily close receive the same comparison;
- financial values retain units and report periods;
- empty lists are distinguished from provider failures.

Section states are `HEALTHY`, `DEGRADED`, `STALE`, `UNVERIFIED`, and `UNAVAILABLE`. Disagreement is displayed; the application does not silently select the more convenient value.

## 9. Analysis Dimensions

The research snapshot exposes the following groups.

### 9.1 Market Overview

Price, absolute and percentage change, open/high/low/previous close, amplitude, volume, amount, turnover, volume ratio, price limits, total market value, circulating market value, PE, PB, and PS when available.

### 9.2 Trend And Direction

- SMA and EMA: 5, 10, 20, 30, 60, 120, and 250;
- moving-average ordering and slope;
- BIAS;
- ADX and DMI;
- Aroon;
- TRIX;
- Ichimoku cloud;
- Supertrend;
- Donchian channels;
- daily, weekly, and monthly alignment.

### 9.3 Momentum

- MACD 12/26/9;
- RSI 6/12/24;
- Stochastic RSI;
- KDJ 9/3/3;
- CCI;
- ROC;
- Williams %R;
- PSY.

### 9.4 Volatility And Price Structure

- Bollinger Bands 20/2;
- ATR and NATR 14;
- 20-day historical volatility and percentile;
- channel width;
- gaps;
- recent swing highs and lows;
- 20/60/120-day support and resistance;
- algorithmically anchored Fibonacci retracement levels;
- breakout and failed-breakout candidates.

### 9.5 Volume And Money Flow

- 5/20-day average volume and volume ratio;
- OBV;
- MFI 14;
- Chaikin Money Flow;
- Accumulation/Distribution;
- price-volume confirmation and divergence;
- Eastmoney/Sina capital-flow history.

### 9.6 Relative Strength And Risk

- 20/60/120/250-day return;
- excess return versus沪深 300;
- beta and correlation;
- annualized volatility;
- maximum drawdown;
- Sharpe, Sortino, and Calmar ratios;
- historical VaR and CVaR;
- skewness and kurtosis.

### 9.7 Capital, Fundamentals, Valuation, And Events

- margin financing, block trades, dragon-tiger list, shareholder count, unlock pressure, and dividends;
- profitability, growth, solvency, operations, cash flow, and a DuPont breakdown where statement fields support it;
- PE, PB, PS, current report period, research ratings, target price, and EPS consensus coverage;
- industry, concepts, research reports, news, and announcements.

## 10. Signal Semantics

The application does not collapse all analysis into a black-box buy/sell score. Each indicator card shows:

- indicator name and parameter set;
- current value;
- daily/weekly/monthly timeframe;
- descriptive state such as strong, neutral, weak, overbought, oversold, expanding, or contracting;
- the exact comparison that triggered the state;
- a compact recent series;
- calculation date and data status.

Group summaries describe trend, momentum, volatility, volume, and relative strength independently. The agent may synthesize conflicts but must preserve the conflicting evidence.

## 11. Agent Tools And Output

The model receives bounded tools with explicit schemas:

- `searchStock(query)`;
- `getResearchSnapshot(code)`;
- `getTechnicalAnalysis(code, timeframe)`;
- `getCapitalAndFundamentals(code)`;
- `getEventsAndResearch(code)`;
- `explainIndicator(code, indicator, timeframe)`.

The structured synthesis contains:

- factual summary;
- trend and market regime;
- technical evidence;
- capital and fundamental evidence;
- conflicting signals;
- event risks;
- missing or degraded data;
- source citations;
- learning-oriented conclusion and disclaimer.

The system prompt forbids invented current prices, unsupported citations, hidden replacement of missing data, and direct trade instructions.

## 12. Model Configuration And Secrets

`application.yml` imports `optional:file:./config/application-local.yml`. The repository commits only `config/application-local.yml.example`.

The local file contains:

```yaml
spring:
  ai:
    openai:
      api-key: <your-local-api-key>
      base-url: "https://your-openai-compatible-endpoint"
      chat:
        options:
          model: "your-model-name"
          temperature: 0.2
```

The exact property structure is verified against the selected Spring AI patch version during implementation. Secrets are never returned by APIs, logged, embedded in frontend assets, or committed. When the key is absent, the application starts normally and `/api/agent/status` reports `DISABLED_CONFIGURATION_MISSING`.

## 13. REST API

Initial endpoints:

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/stocks/search?q=` | Search and normalize stock codes/names |
| GET | `/api/stocks/{code}/snapshot` | Aggregated partial research snapshot |
| GET | `/api/stocks/{code}/technical?timeframe=DAILY` | Technical indicator cards and series |
| GET | `/api/stocks/{code}/sources` | Quality score and source diagnostics |
| POST | `/api/agent/analyze` | Structured AI synthesis for one snapshot |
| POST | `/api/agent/chat` | Follow-up question constrained to current stock context |
| GET | `/api/agent/status` | Model availability without exposing configuration |
| GET | `/api/system/data-sources` | Provider health, cooldown, and last success |

Error responses use one stable problem-details format. Snapshot endpoints return HTTP 200 for partial success and encode per-section failures. They return a 4xx error only for invalid or unsupported securities and a 5xx error only when no core identification or quote path can produce a usable response.

## 14. Frontend Design

The approved design combines the light information architecture of `D:/Code/Java_Code/love530/website/python-a` with a restrained purple interaction system inspired by Linear.

Core tokens:

- canvas `#f7f7f4`;
- soft canvas `#fafaf7`;
- surface `#ffffff`;
- primary ink `#26251e`;
- body `#5a5852`;
- muted `#807d72`;
- hairline `#e6e5e0`;
- strong hairline `#cfcdc4`;
- primary purple `#7158d9`;
- soft purple `#eeeafd`;
- A-share up red `#d83b53`;
- A-share down green `#1f8a65`.

Purple is reserved for the brand mark, primary command, selected tab, focus ring, and selected control. Pale blue, green, orange, gold, and purple indicator accents keep the dashboard from becoming a one-hue interface. No gradient, decorative glow, or oversized marketing hero is used.

The first screen is the actual research workbench:

- compact product header and symbol search;
- quote strip with stable cell dimensions;
- tabs for overview, technical analysis, capital, fundamentals, valuation, events, and source quality;
- four-column technical indicator grid on desktop, three/two columns on intermediate widths, and one column on mobile;
- individual cards for repeated indicators, never cards nested inside cards;
- each card includes value, status, mini-series, and trigger evidence;
- visible loading, partial, empty, stale, and error states;
- keyboard navigation, visible focus, 44px touch targets, reduced-motion support, and non-color status labels.

## 15. Testing Strategy

Development follows red-green-refactor for domain behavior.

### 15.1 Offline Tests

- security-code and market normalization;
- provider parsers against versioned captured fixtures;
- numeric units and field mappings;
- all validation rules;
- every custom technical formula and signal boundary;
- aggregation under partial failure;
- quality-score breakdown;
- agent tool schemas and disabled-model behavior;
- controller contracts with MockMvc;
- frontend JavaScript state rendering for success, partial, stale, and failure responses.

HTTP provider tests use a local stub server; they never depend on public network availability.

### 15.2 Live Data Verification

Live tests are tagged `external` and excluded from the default unit-test phase. `scripts/verify-data.ps1` and `.sh` run them explicitly against representative securities:

- `600519` for Shanghai main board;
- `000001` for Shenzhen main board;
- `300750` for ChiNext.

They assert matching symbol identity, non-empty quotes and bars, sane OHLC relations, recent trading dates, and cross-source consistency. Output lists every source, timestamp, fallback, discrepancy, and failure. A provider outage fails or degrades only the checks it owns; the report never labels unavailable live data as passing.

### 15.3 UI Verification

Playwright checks desktop and mobile viewports, keyboard navigation, card-grid collapse, loading/error states, non-overlap, and horizontal overflow. Screenshots are reviewed against the approved light-purple reference. Charts also receive a canvas-pixel nonblank check.

## 16. Documentation

`README.md` will include:

- project purpose and learning map;
- architecture and data-flow diagram;
- one-command Windows and Unix startup;
- local model configuration;
- data-source table and limitations;
- default and external test commands;
- troubleshooting for JDK download, provider blocks, missing API keys, and stale market data;
- legal and investment disclaimer.

`AGENTS.md` will document module ownership, architectural rules, provider etiquette, secret handling, test commands, UI rules, and the requirement to preserve provenance and partial-failure semantics.

## 17. Acceptance Criteria

The first release is accepted when:

1. A clean Windows checkout starts through `start.ps1` without relying on the machine JDK or Maven.
2. The application also exposes standard Maven Wrapper commands for developers with Java 21.
3. A user can search or enter a supported A-share code and receive a partial-success research snapshot.
4. The technical page renders the approved multi-card light-purple layout and all documented indicator families.
5. Every displayed external datum exposes source, time, status, and validation details.
6. The application visibly degrades when a provider fails and does not fabricate replacements.
7. AI analysis works with a configured OpenAI-compatible endpoint and is cleanly disabled without it.
8. Default offline tests pass without network access.
9. Explicit live verification produces a source-by-source report for the three representative securities.
10. Desktop and mobile visual checks show no overlap, blank charts, or horizontal page overflow.
11. `README.md` and `AGENTS.md` explain how to run, learn, test, and extend the project.
