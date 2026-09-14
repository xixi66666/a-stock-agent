# 大盘指数条与两行行情卡片设计

## 1. 目标

在工作台顶部行情卡片中新增一行「大盘指数条」，展示上证指数、深证成指、创业板指的点位与涨跌幅；卡片原有全部内容（股票身份、现价、八项指标、数据质量）移到第二行。指数数据优先使用同花顺 Financial-API 指数快照，腾讯基准日线仅在主源不可用时局部回退，状态与来源全程保留。

## 2. 范围

### 2.1 本阶段包含

- `BenchmarkId` 新增 `SHANGHAI_COMPOSITE`、`SHENZHEN_COMPONENT`、`CHI_NEXT`，并抽出同花顺代码映射。
- 新域记录 `IndexQuote` 与 `IndexQuoteSection`，不复用 `SecurityId`。
- `HithinkFinanceClient.fetchIndexQuotes(List<BenchmarkId>)`：一次批量请求 `/api/a-share-index/prices/snapshot`，逐指数独立状态。
- 新网关 `IndexQuoteGateway`：同花顺主源，腾讯基准日线（最近两根）局部回退。
- `MarketIndexService` + `GET /api/market/indices`。
- 工作台卡片两行布局：第一行大盘指数条，第二行原有内容；桌面、平板、移动端响应式适配。
- 离线契约测试、网关测试、Controller 测试、Playwright 四视口测试，以及一个外部 live 验证用例。

### 2.2 本阶段不包含

- 指数 K 线、分时、成交额、振幅、领涨板块等扩展字段。
- 板块指数（同花顺 `.TI` 概念/行业指数）。
- 指数点击跳转、自选指数或用户自定义列表。
- 轮询或自动刷新；沿用现有手动刷新与进入页面时加载。

## 3. 架构

```text
GET /api/market/indices
        |
        v
MarketIndexService (固定三指数)
        |
        v
IndexQuoteGateway
        |
        +-- HithinkIndexQuoteGateway
        |       +-- HithinkFinanceClient.fetchIndexQuotes   (主源，批量)
        |       +-- BenchmarkDataGateway.bars               (回退，最近两根日线)
        |
        v
MarketIndexSnapshot(List<IndexQuoteSection>)
```

分层约束：

- Provider 适配器只做协议、身份与数值校验，不做展示决策。
- 网关负责主备切换，失败保持指数局部（一个指数失败不影响其他指数）。
- 前端只渲染状态与数值，不计算点位或涨跌幅。

## 4. 领域契约

### 4.1 BenchmarkId 扩展

```java
SHANGHAI_COMPOSITE("sh000001", "上证指数"),
SHENZHEN_COMPONENT("sz399001", "深证成指"),
CHI_NEXT("sz399006", "创业板指");
```

新增 `hithinkCode()`：`sh000001` → `000001.SH`，`sz399001` → `399001.SZ`。`fetchBenchmarkBars` 复用该方法，不再内联拼接。

### 4.2 IndexQuote

```java
record IndexQuote(
    BenchmarkId benchmark,
    String displayName,
    BigDecimal lastPoint,
    BigDecimal previousClose,
    BigDecimal changeAmount,
    BigDecimal changePercent,
    Instant quotedAt)
```

- `lastPoint`、`previousClose` 必须为正数，否则该指数契约失败。
- `changeAmount`、`changePercent` 允许为空：源字段缺失时保留空值，不推导、不补零。
- `displayName` 使用枚举展示名，不依赖上游名称字段。

### 4.3 IndexQuoteSection

```java
record IndexQuoteSection(BenchmarkId benchmark, String displayName, DataSection<IndexQuote> quote)
```

`DataSection` 的 `UNAVAILABLE` 不携带 payload，因此指数身份必须放在分区之外，前端才能在不可用时仍定位到对应格子。

### 4.4 MarketIndexSnapshot

```java
record MarketIndexSnapshot(List<IndexQuoteSection> indices)
```

固定顺序：上证指数、深证成指、创业板指。

## 5. 数据源与状态

### 5.1 同花顺主源

- 端点：`GET /api/a-share-index/prices/snapshot?thscodes=000001.SH,399001.SZ,399006.SZ`。
- 每次请求携带全部指数，逐行按 `thscode` 校验身份；返回未请求的 `thscode`、重复行或非数字字段视为批次契约失败。
- 缺少某个请求指数：该指数 `UNAVAILABLE`，其余指数不受影响。
- 成功解析的指数统一为 `UNVERIFIED`，issues 说明源时间是数据就绪时间而非成交时间；`quotedAt` 取响应 `timestamp`（缺失保留空）。
- 未配置或未启用 API Key：全部指数 `UNAVAILABLE`，不发起请求。

### 5.2 腾讯基准日线回退

- 仅当某指数同花顺分区不可用（或无 payload）时调用 `BenchmarkDataGateway.bars(benchmark)`。
- 取最近两根日线：`lastPoint = 最后收盘`，`previousClose = 前一根收盘`，`changeAmount = last - prev`，`changePercent = change * 100 / prev`（scale 4，HALF_UP）。
- 少于两根日线或请求失败：该指数 `UNAVAILABLE`，issues 合并主源与回退原因。
- 状态 `UNVERIFIED`，issues 注明「腾讯基准日线回退：最新已完成交易日收盘口径，不是实时行情」并带上收盘日期。
- provenance 沿用回退来源，`fallbackProvider = "HiThink Finance"`，与现有 `HithinkResearchGateway.fallback` 语义一致。

### 5.3 状态语义

| 状态 | 含义 | UI |
| --- | --- | --- |
| `UNVERIFIED`（同花顺） | 数据可用，源时间口径未验证 | 正常展示，小角标「未验证」 |
| `UNVERIFIED`（腾讯日线回退） | 收盘口径推导 | 正常展示，小角标「收盘口径」 |
| `STALE` | 源数据陈旧 | 正常展示，小角标「陈旧」 |
| `UNAVAILABLE` | 无数据 | 显示「不可用」与原因 title |
| 接口整体失败 | HTTP/网络错误 | 三格显示「不可用」 |

禁止把缺失值渲染为 `0`、`--` 之外的占位数值或编造行情。

## 6. REST 契约

`GET /api/market/indices` → HTTP 200：

```json
{
  "indices": [
    {
      "benchmark": "SHANGHAI_COMPOSITE",
      "displayName": "上证指数",
      "quote": {
        "status": "UNVERIFIED",
        "payload": {
          "benchmark": "SHANGHAI_COMPOSITE",
          "displayName": "上证指数",
          "lastPoint": 3123.45,
          "previousClose": 3113.2,
          "changeAmount": 10.25,
          "changePercent": 0.33,
          "quotedAt": "2026-09-14T01:36:13Z"
        },
        "provenance": {
          "provider": "HiThink Finance",
          "sourceUrl": "https://fuyao.aicubes.cn/api/a-share-index/prices/snapshot?thscodes=000001.SH,399001.SZ,399006.SZ",
          "providerTimestamp": "2026-09-14T01:36:00Z",
          "fetchedAt": "2026-09-14T01:36:10Z",
          "cached": false,
          "fallbackProvider": null
        },
        "issues": ["同花顺指数行情源时间是数据就绪时间，不是成交时间"]
      }
    }
  ]
}
```

- 任何指数不可用时 `payload` 为 `null`，`issues` 非空。
- 接口不因单指数失败返回非 200。

## 7. UI 设计

### 7.1 布局

- `.security-overview` 保持四列网格；新增 `.market-strip` 作为第一个子元素，`grid-column: 1 / -1` 独占第一行，原有四块按 HTML 顺序自动落到第二行。
- 大盘条与下方内容之间用一条 hairline 分隔。
- 每个指数：名称（muted 12px）+ 点位（650 字重，tabular）+ 涨跌幅（tabular），三列等宽。

### 7.2 语义与状态

- A 股红涨绿跌，同时输出 `↑`/`↓` 文本，颜色不是唯一信息载体。
- 角标只在语义与实时数据不同时显示：`陈旧`（STALE）、`收盘口径`（腾讯日线回退）。同花顺实时快照的 `UNVERIFIED` 不再显示「未验证」角标，源时间口径仍保留在 `title` 提示与 provenance 中。
- 点位与涨跌幅为「不可用」时显示文字，`title` 暴露 issues 原因；禁止用占位数值替代缺失数据。
- 不使用渐变、装饰元素或营销文案；沿用既有设计令牌。

### 7.3 响应式

- ≤1180、≤820：大盘条保持整行三列。
- ≤520：三个指数纵向堆叠，名称居左、数值居右。
- 四个基准视口（1440x1000、1024x768、768x1024、390x844）不得横向溢出、不得与固定头部重叠。

### 7.4 加载与刷新

- 与个股快照并行加载，互不阻塞；使用 `loadGeneration` 防止旧响应覆盖新代码。
- 顶部刷新按钮同时刷新个股与大盘。
- 未选择股票时不请求大盘。

## 8. 测试与验证

- `BenchmarkIdTest`：同花顺代码映射。
- `HithinkFinanceClientTest`：批量快照成功、缺行局部不可用、异常行局部不可用、未请求身份整体拒绝、未配置 Key 不请求。
- `HithinkIndexQuoteGatewayTest`：主源健康不触发回退、主源失败用两根日线推导数值与状态、两者都失败返回不可用、部分指数局部回退。
- `MarketControllerTest`：JSON 形状与 200 状态。
- `tests/ui/market-indices.spec.js`：健康、局部不可用、接口失败三种渲染与四视口截图。
- 外部校验：`HithinkLiveDataIT` 增加指数快照断言（`@Tag("external")`，`-Pexternal` 才运行），确认真实字段名与状态。

## 9. 风险与约束

- 真实响应字段以线上契约（`PriceSnapshotItem`）为准，若缺少 `price_change` 字段则涨跌幅显示为空，必须通过 live 验证确认，不得在测试中假设。
- 同花顺每次页面加载只发一次批量请求，不引入并行循环；腾讯回退最多三个独立请求。
- 不在前端做任何确定性计算。
- 设计文档先行；提交需用户确认。
