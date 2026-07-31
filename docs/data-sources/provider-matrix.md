# Provider Matrix

| Capability | Primary | Cross-check / fallback | Risk policy | Normalized output |
|---|---|---|---|---|
| Real-time quote | Tencent Finance | None | Preferred, no Eastmoney dependency | `Quote` |
| Front-adjusted daily K-line | Tencent Finance | Baidu Stock | At least 260 valid bars; compare latest date and close | `List<DailyBar>` |
| Sector and concept membership | Eastmoney | Section-local unavailable | Shared serialized throttle | `List<String>` |
| Stock fund flow | Eastmoney | Sina daily fund flow | Eastmoney cooldown falls back to Sina | `List<FundFlow>` |
| Margin, block trade, holders, unlock, dividend, dragon-tiger | Eastmoney | Section-local unavailable | Shared serialized throttle | `CapitalData` |
| Financial statements | Sina Finance | Section-local unavailable | Bounded retry and timeout | `FundamentalData` |
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
| Consistency | 30 | Tencent and Baidu recent K-line agreement |
| Completeness | 25 | Core and optional section availability |
| Authority | 15 | Preferred or official source provenance |

The score is an engineering data-quality signal, not a stock rating or investment recommendation.

Industry valuation keeps the target row separate from peer samples, excludes
invalid/non-positive ratios, preserves sample counts and provenance, and does
not fabricate a percentile when the peer sample is insufficient. Consensus EPS
uses the newest report per institution and median values; fewer than two
institutions do not create a directional EPS signal.
