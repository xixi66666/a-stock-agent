# Provider Matrix

按 2026-09-15 的网关装配核对。同花顺需在本地启用并配置密钥；未配置时使用可用的备用来源，不代表同花顺实时验证已通过。

| Capability | Primary | Cross-check / fallback | Risk policy | Normalized output |
|---|---|---|---|---|
| Real-time quote | HiThink Finance | Tencent → Eastmoney → Sina | Fill missing fields only when primary is usable; otherwise whole-section fallback | `Quote` |
| Front-adjusted daily K-line | HiThink Finance | Tencent fallback; Baidu independent cross-check | At least 260 valid bars; compare latest date and close | `List<DailyBar>` |
| Benchmark daily bars | HiThink Finance | Tencent | Preserve index identity and source | `List<DailyBar>` |
| Three major index quotes | HiThink Finance batch snapshot | Benchmark daily-bar gateway (HiThink → Tencent) | Missing index falls back to completed-day close; marked UNVERIFIED, not real-time | `MarketIndexSnapshot` |
| Valuation snapshot | HiThink Finance | Section-local unavailable | 5 minute cache; preserve negative and missing values, never substitute a different ratio | `ValuationSnapshot` |
| Sector and concept membership | Eastmoney | Section-local unavailable | Shared serialized throttle | `List<String>` |
| Stock fund flow | Eastmoney | Sina daily fund flow | Eastmoney cooldown falls back to Sina | `List<FundFlow>` |
| Dividend and dragon-tiger | HiThink Finance | Existing Eastmoney capital data | Preserve component provenance; dividend cache 6 hours, dragon-tiger cache 1 hour | `CapitalData` |
| Margin, block trade, holders, unlock | Eastmoney | Section-local unavailable | Shared serialized throttle | `CapitalData` |
| Financial statements and history | HiThink Finance | Sina Finance | Up to 8 annual periods for HiThink; 30 minute history cache; preserve reporting basis | `FundamentalData` / `FinancialStatementHistory` |
| Institution research | Eastmoney Report API | Empty successful list | Shared serialized throttle | `List<ResearchItem>` |
| Industry peer valuation | Eastmoney `clist/get` batch | Section-local unavailable | Shared serialized throttle; 15 minute cache | `IndustryValuationData` (PE/PB median and percentile) |
| Stock news | Eastmoney Search API | Empty successful list | Shared serialized throttle | `List<NewsItem>` |
| Announcements | CNInfo official | Section-local unavailable | Dynamic organization ID lookup | `List<Announcement>` |

## Eastmoney Guardrail

All Eastmoney hosts use the same `ProviderThrottle` instance. Calls are serialized across capabilities, use a minimum one-second interval plus jitter, reuse the JDK HTTP client, and enter provider cooldown on blocking responses. A `403` is never retried. Connection failures, `429`, and `5xx` use at most two bounded retries.

## Core Acceptance

A live verification passes only when each requested security returns the correct identity, a positive quote, at least 260 ascending daily bars, valid OHLC relationships, and a technical snapshot. Optional providers may be degraded or unavailable and are recorded in the generated evidence report.

## Quality Score

The score totals 100 points:

| Component | Maximum | Evidence |
|---|---:|---|
| Freshness | 30 | Quote/source timestamps and age |
| Consistency | 30 | Selected primary bars (HiThink or Tencent fallback) versus Baidu |
| Completeness | 25 | Quote payload, at least 260 bars and usable technical analysis |
| Authority | 15 | Current quote provider-name check in `ResearchAggregationService` |

The score is an engineering data-quality signal, not a stock rating or investment recommendation.

当前计分实现为：行情和日线均为 HEALTHY/DEGRADED 时，时效性 30 分，否则 15；一致性通过为 30，否则 0；核心完整为 25，否则 10；来源名称包含 `Tencent` 或完全等于 `HiThink Finance` 时，权威性 15，否则 8。它没有单独按所有可选分区或混合来源逐项评分，不能当作全面的数据审计结论。

Industry valuation keeps the target row separate from peer samples, excludes
invalid/non-positive ratios, preserves sample counts and provenance, and does
not fabricate a percentile when the peer sample is insufficient. Consensus EPS
uses the newest report per institution and median values; fewer than two
institutions do not create a directional EPS signal.
