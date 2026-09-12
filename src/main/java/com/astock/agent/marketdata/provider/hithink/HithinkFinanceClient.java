package com.astock.agent.marketdata.provider.hithink;

import com.astock.agent.marketdata.model.*;
import com.astock.agent.marketdata.provider.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;

/** Bounded REST adapter. Remote errors are summarized without reflecting upstream text. */
public final class HithinkFinanceClient {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final ProviderHttpClient http;
    private final Clock clock;
    private final String apiKey;
    private final URI base;
    private final com.github.benmanes.caffeine.cache.Cache<SecurityId, String> names =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(10000)
                    .expireAfterWrite(Duration.ofDays(1)).build();

    public HithinkFinanceClient(ProviderHttpClient http, Clock clock, String apiKey, URI base) {
        this.http = http;
        this.clock = clock;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.base = base;
    }

    public DataSection<Quote> fetchQuote(SecurityId security) {
        URI uri = base.resolve("/api/a-share/prices/snapshot?thscodes=" + thscode(security));
        try {
            JsonNode data = get(uri);
            if (data.path("item").isEmpty()) return DataSection.unavailable("同花顺行情成功返回空结果");
            if (data.path("item").size() != 1) throw new ContractFailure("行情返回行数不符");
            JsonNode row = data.path("item").get(0);
            identity(row, security);
            BigDecimal price = requiredPositive(row, "last_price");
            BigDecimal previous = requiredPositive(row, "prev_price");
            BigDecimal open = requiredPositive(row, "open_price");
            BigDecimal high = requiredPositive(row, "high_price");
            BigDecimal low = requiredPositive(row, "low_price");
            if (high.compareTo(open.max(price)) < 0 || low.compareTo(open.min(price)) > 0)
                throw new ContractFailure("行情 OHLC 关系无效");
            BigDecimal volume = number(row, "volume"), amount = number(row, "turnover");
            if ((volume != null && volume.signum() < 0) || (amount != null && amount.signum() < 0))
                throw new ContractFailure("成交量额不能为负");
            var issues = new ArrayList<String>();
            String name = row.path("name").asText(null);
            if (name == null || name.isBlank()) name = names.getIfPresent(security);
            if (name == null) {
                try {
                    JsonNode meta = get(base.resolve("/api/meta/tickers/search?q=" + thscode(security)
                            + "&asset_type=a-share&limit=5"));
                    for (JsonNode item : meta.path("item")) {
                        if (!item.path("thscode").asText().equals(thscode(security))) continue;
                        identity(item, security);
                        if (!item.path("currency").asText().equals("CNY")
                                || !item.path("asset_type").asText().equals("a-share"))
                            throw new ContractFailure("证券元信息类别或币种不符");
                        name = item.path("name").asText();
                        if (!name.isBlank()) names.put(security, name);
                        break;
                    }
                } catch (Exception failure) { issues.add("证券名称查询不可用"); }
            }
            if (name == null || name.isBlank()) { name = security.code(); issues.add("证券名称缺失，仅显示代码"); }
            var quote = new Quote(security, name, price, previous, open, high, low,
                    number(row,"price_change"), number(row,"price_change_ratio_pct"), volume, amount,
                    null,null,null,null,null,null,null,null,null,null,null);
            Instant time = timestamp(data.path("timestamp"));
            var source = new Provenance(ProviderId.HITHINK.displayName(),uri,time,clock.instant(),false,null);
            issues.add("同花顺行情金额为元、成交量为股；源时间是数据就绪时间，不是成交时间；成交时间未提供");
            issues.add("换手率、量比、市值、涨跌停价等未提供；估值见独立同花顺估值快照");
            if (time != null && time.isBefore(clock.instant().minus(Duration.ofDays(7))))
                return DataSection.stale(quote,source,issues);
            return DataSection.unverified(quote,source,issues);
        } catch (Exception failure) { return DataSection.unavailable("同花顺行情不可用：" + safeFailure(failure)); }
    }

    private static BigDecimal requiredPositive(JsonNode row, String field) {
        BigDecimal value = number(row,field);
        if (value == null || value.signum() <= 0) throw new ContractFailure("价格缺失或非正数");
        return value;
    }

    public DataSection<List<DailyBar>> fetchDailyBars(SecurityId security) {
        return history(thscode(security), false);
    }

    public DataSection<List<CapitalData.DividendRecord>> fetchDividends(SecurityId security) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        URI uri = base.resolve("/api/a-share/corporate-actions/adjustment-factors?thscode=" + thscode(security)
                + "&from=" + today.minusYears(10) + "&to=" + today);
        try {
            JsonNode data = get(uri);
            identity(data,security);
            var result = new ArrayList<CapitalData.DividendRecord>();
            for (JsonNode row : data.path("item")) {
                if (!row.path("ticker").asText().equals(security.code())) throw new ContractFailure("分红证券身份不符");
                Instant ex = timestamp(row.path("ex_date_ms"));
                if (ex == null) throw new ContractFailure("除权除息日期缺失");
                LocalDate date = ex.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
                if (date.isAfter(today) || date.isBefore(today.minusYears(10))) throw new ContractFailure("分红日期超出窗口");
                BigDecimal cash = number(row,"dividend_per_share"), bonus = number(row,"per_share_bonus");
                if ((cash != null && cash.signum() < 0) || (bonus != null && bonus.signum() < 0))
                    throw new ContractFailure("分红送股数值不能为负");
                result.add(new CapitalData.DividendRecord(date,cash,null,
                        bonus == null ? null : bonus.multiply(BigDecimal.TEN),"已发生除权除息事件"));
            }
            result.sort(java.util.Comparator.comparing(CapitalData.DividendRecord::exDate).reversed());
            var source = new Provenance(ProviderId.HITHINK.displayName(),uri,null,clock.instant(),false,null);
            return DataSection.unverified(List.copyOf(result),source,List.of(
                    "同花顺近十年公司行动；每股现金分红为税前元，送股已换算为每十股；转增比例、登记日及源更新时间未提供"));
        } catch (Exception failure) { return DataSection.unavailable("同花顺分红不可用："+safeFailure(failure)); }
    }

    /** Latest all-market dragon-tiger list, narrowed to the requested security. */
    public DataSection<List<CapitalData.DragonTigerRecord>> fetchDragonTiger(SecurityId security) {
        URI uri = base.resolve("/api/a-share/special-data/dragon-tiger-list?board_type=all");
        try {
            JsonNode data = requestData(uri);
            String tradeDateText = data.path("trade_date").asText("");
            if (tradeDateText.isBlank()) throw new ContractFailure("龙虎榜交易日期缺失");
            LocalDate tradeDate = LocalDate.parse(tradeDateText);
            var result = new ArrayList<CapitalData.DragonTigerRecord>();
            JsonNode items = data.path("stock_items");
            if (!items.isArray()) throw new ContractFailure("龙虎榜股票列表缺失");
            for (JsonNode row : items) {
                if (!thscode(security).equals(row.path("thscode").asText())
                        || !security.code().equals(row.path("ticker").asText())) continue;
                BigDecimal netBuy = number(row, "net_value");
                if (netBuy != null && netBuy.abs().compareTo(new BigDecimal("1000000000000")) > 0)
                    throw new ContractFailure("龙虎榜净买入金额超出合理范围");
                String reason = row.path("limit_reason").asText("");
                result.add(new CapitalData.DragonTigerRecord(tradeDate,
                        reason.isBlank() ? "龙虎榜" : reason, netBuy, null));
                break;
            }
            Instant time = timestamp(data.path("timestamp"));
            var source = new Provenance(ProviderId.HITHINK.displayName(),uri,time,clock.instant(),false,null);
            var issues = new ArrayList<String>();
            issues.add("同花顺为最新交易日全市场龙虎榜；净买入单位为元");
            issues.add("接口未提供个股换手率，turnoverPercent 保持空值");
            if (result.isEmpty()) issues.add("该证券不在最新交易日龙虎榜中");
            return DataSection.unverified(List.copyOf(result),source,issues);
        } catch (Exception failure) {
            return DataSection.unavailable("同花顺龙虎榜不可用：" + safeFailure(failure));
        }
    }

    public DataSection<List<DailyBar>> fetchBenchmarkBars(BenchmarkId benchmark) {
        if (benchmark == null) return DataSection.unavailable("基准指数未指定");
        String code = benchmark.tencentCode();
        return history(code.substring(2) + "." + code.substring(0,2).toUpperCase(java.util.Locale.ROOT), true);
    }

    private DataSection<List<DailyBar>> history(String code, boolean index) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        LocalDate startDate = today.minusYears(3);
        long start = startDate.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        URI uri = base.resolve("/api/" + (index ? "a-share-index" : "a-share")
                + "/prices/historical?thscode=" + code + "&interval=1d&start=" + start
                + "&end=" + clock.millis() + (index ? "" : "&adjust=forward"));
        try {
            JsonNode data = get(uri);
            if (data.path("item").isEmpty()) return DataSection.unavailable("同花顺日线成功返回空结果");
            if (data.path("item").size() > 1200) throw new ContractFailure("日线数量超出有界窗口");
            var sorted = new TreeMap<LocalDate,DailyBar>();
            for (JsonNode row : data.path("item")) {
                // Historical rows omit identity in the contract; reject explicit mismatches if supplied.
                if (row.hasNonNull("thscode") && !row.path("thscode").asText().equals(code))
                    throw new ContractFailure("日线证券身份不符");
                Instant time = timestamp(row.path("date_ms"));
                if (time == null) throw new ContractFailure("日线日期缺失");
                LocalDate date = time.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
                if (date.isAfter(today) || date.isBefore(startDate)) throw new ContractFailure("日线日期超出请求窗口");
                var bar = new DailyBar(date,requiredPositive(row,"open_price"),requiredPositive(row,"high_price"),
                        requiredPositive(row,"low_price"),requiredPositive(row,"close_price"),
                        number(row,"volume"),number(row,"turnover"));
                if (bar.volumeShares() == null) throw new ContractFailure("日线成交量缺失");
                if (sorted.put(date,bar) != null) throw new ContractFailure("日线重复日期");
            }
            var bars = new ArrayList<>(sorted.values());
            var validation = new com.astock.agent.marketdata.validation.MarketDataValidator().validateBars(bars);
            if (!validation.isEmpty()) throw new ContractFailure("日线 OHLC、成交量或专业历史长度校验失败");
            bars = new ArrayList<>(bars.subList(Math.max(0,bars.size()-520),bars.size()));
            Instant time = timestamp(data.path("timestamp"));
            var source = new Provenance(ProviderId.HITHINK.displayName(),uri,time,clock.instant(),false,null);
            var issues = new ArrayList<String>();
            issues.add(index ? "同花顺指数日线，无复权概念" : "同花顺前复权日线；成交量单位为股，成交额单位为元");
            if (bars.getLast().date().isBefore(today.minusDays(7)))
                return DataSection.stale(List.copyOf(bars),source,List.of("同花顺日线超过七天未更新",issues.getFirst()));
            if (time == null || time.isAfter(clock.instant().plusSeconds(300)))
                return DataSection.unverified(List.copyOf(bars),source,List.of("日线源时间缺失或超前",issues.getFirst()));
            return new DataSection<>(SectionStatus.HEALTHY,java.util.Optional.of(List.copyOf(bars)),
                    java.util.Optional.of(source),issues);
        } catch (Exception failure) { return DataSection.unavailable("同花顺日线不可用："+safeFailure(failure)); }
    }

    public DataSection<ValuationSnapshot> fetchValuation(SecurityId security) {
        URI uri = base.resolve("/api/a-share/valuations/snapshot?thscodes=" + thscode(security));
        try {
            JsonNode data = get(uri);
            if (data.path("item").isEmpty()) return DataSection.unavailable("同花顺估值成功返回空结果");
            if (data.path("item").size() != 1) throw new ContractFailure("估值返回行数不符");
            JsonNode row = data.path("item").get(0);
            identity(row, security);
            var value = new ValuationSnapshot(security, number(row, "pe_ttm"), number(row, "pe_mrq"),
                    number(row, "pb_mrq"), number(row, "ps_ttm"), number(row, "pcf_ttm"));
            Instant time = timestamp(data.path("timestamp"));
            var source = new Provenance(ProviderId.HITHINK.displayName(), uri, time, clock.instant(), false, null);
            if (value.peTtm() == null && value.peMrq() == null && value.pbMrq() == null
                    && value.psTtm() == null && value.pcfTtm() == null)
                return DataSection.unverified(value, source, List.of("估值指标全部缺失"));
            if (time == null) return DataSection.unverified(value, source, List.of("估值源时间缺失"));
            if (time.isAfter(clock.instant().plusSeconds(300)))
                return DataSection.unverified(value, source, List.of("估值源时间超前"));
            if (time.isBefore(clock.instant().minus(Duration.ofDays(7))))
                return DataSection.stale(value, source, List.of("估值源时间超过七天"));
            return DataSection.healthy(value, source);
        } catch (Exception failure) {
            return DataSection.unavailable("同花顺估值不可用：" + safeFailure(failure));
        }
    }

    /** Annual CNY statements only: quarterly cumulative semantics are not specified upstream. */
    public DataSection<FinancialStatementHistory> fetchStatementHistory(SecurityId security) {
        var rows = new TreeMap<LocalDate, Map<String, JsonNode>>();
        var issues = new ArrayList<String>();
        issues.add("同花顺财报仅使用年报，金额单位为元；未提供流动负债、股本和归母权益，相关信号无法评估");
        Instant sourceTime = null;
        for (String type : List.of("income-statements", "balance-sheets", "cash-flow-statements")) {
            URI uri = base.resolve("/api/a-share/financials/" + type + "?thscode=" + thscode(security)
                    + "&period=annual&limit=8");
            try {
                JsonNode data = get(uri);
                var table = new TreeMap<LocalDate, JsonNode>();
                for (JsonNode row : data.path("item")) {
                    identity(row, security);
                    // Reject only the affected table before merging it into the shared history.
                    List<String> fields = switch (type) {
                        case "income-statements" -> List.of("operating_income", "operating_costs",
                                "net_profit", "parent_holder_net_profit");
                        case "balance-sheets" -> List.of("assets_total", "total_debt", "total_current_assets");
                        default -> List.of("act_cash_flow_net");
                    };
                    for (String field : fields) number(row, field);
                    if (!row.path("period").asText().equals("annual") || !row.path("currency").asText().equals("CNY"))
                        throw new ContractFailure("报表周期或币种不符");
                    Instant end = timestamp(row.path("period_end_ms"));
                    if (end == null) throw new ContractFailure("缺少报告期");
                    LocalDate date = end.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
                    if (date.getMonthValue() != 12 || date.getDayOfMonth() != 31 || end.isAfter(clock.instant()))
                        throw new ContractFailure("年报报告期无效");
                    if (table.put(date, row) != null) throw new ContractFailure("重复报告期");
                    Instant disclosed = timestamp(row.path("report_date_ms"));
                    if (disclosed != null && (disclosed.isBefore(end) || disclosed.isAfter(clock.instant())))
                        throw new ContractFailure("报表披露时间无效");
                }
                if (table.isEmpty()) issues.add(type + "：成功返回空结果");
                for (var entry : table.entrySet()) {
                    rows.computeIfAbsent(entry.getKey(), unused -> new java.util.HashMap<>()).put(type, entry.getValue());
                    Instant disclosed = timestamp(entry.getValue().path("report_date_ms"));
                    issues.add(type + " 报告期=" + entry.getKey() + " 报告日期="
                            + (disclosed == null ? "缺失" : disclosed.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()));
                }
                Instant time = timestamp(data.path("timestamp"));
                if (time != null && !time.isAfter(clock.instant()) && (sourceTime == null || time.isAfter(sourceTime))) sourceTime = time;
            } catch (Exception failure) {
                issues.add(type + "：" + safeFailure(failure));
            }
        }
        if (rows.isEmpty()) return DataSection.unavailable(String.join("；", issues));
        var periods = new ArrayList<FinancialPeriodStatement>();
        try {
            for (var entry : rows.entrySet()) {
                JsonNode income = entry.getValue().getOrDefault("income-statements", JSON.createObjectNode());
                JsonNode balance = entry.getValue().getOrDefault("balance-sheets", JSON.createObjectNode());
                JsonNode cash = entry.getValue().getOrDefault("cash-flow-statements", JSON.createObjectNode());
                periods.add(new FinancialPeriodStatement(entry.getKey(), number(income, "operating_income"),
                        number(income, "operating_costs"), number(income, "net_profit"),
                        number(income, "parent_holder_net_profit"), number(cash, "act_cash_flow_net"),
                        number(balance, "assets_total"), number(balance, "total_debt"),
                        number(balance, "total_current_assets"), null, null, null));
            }
        } catch (Exception failure) { return DataSection.unavailable("同花顺财报数值校验失败"); }
        var source = new Provenance(ProviderId.HITHINK.displayName(),
                base.resolve("/api/a-share/financials/income-statements?thscode=" + thscode(security)
                        + "&period=annual&limit=8"), sourceTime, clock.instant(), false, null);
        var history = new FinancialStatementHistory(security, periods);
        if (periods.getLast().reportPeriod().isBefore(LocalDate.now(clock).minusDays(550)))
            return DataSection.stale(history, source, issues);
        return DataSection.degraded(history, source, issues);
    }

    private JsonNode get(URI uri) throws Exception {
        JsonNode data = requestData(uri);
        if (!data.path("item").isArray()) throw new IllegalArgumentException("schema");
        return data;
    }

    private JsonNode requestData(URI uri) throws Exception {
        if (apiKey.isBlank()) throw new ContractFailure("未启用或未配置 API Key");
        JsonNode envelope = JSON.readTree(http.getWithApiKey(ProviderId.HITHINK, uri, apiKey).utf8Text());
        if (!envelope.path("code").isIntegralNumber()) throw new ContractFailure("缺少业务状态码");
        if (envelope.path("code").asInt() != 0)
            throw new ContractFailure("业务错误 code=" + envelope.path("code").asInt());
        JsonNode data = envelope.path("data");
        if (!data.isObject()) throw new IllegalArgumentException("schema");
        return data;
    }

    private static String thscode(SecurityId security) {
        SecurityId validated = SecurityId.parse(security.code());
        if (!validated.equals(security)) throw new IllegalArgumentException("security mismatch");
        return security.code() + "." + security.exchange().tencentPrefix().toUpperCase(java.util.Locale.ROOT);
    }

    private static void identity(JsonNode row, SecurityId security) {
        if (row == null || !row.path("thscode").asText().equals(thscode(security))
                || !row.path("ticker").asText().equals(security.code()))
            throw new IllegalArgumentException("security mismatch");
    }

    private static BigDecimal number(JsonNode row, String field) {
        JsonNode node = row.path(field);
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalArgumentException("numeric field");
        return node.decimalValue();
    }

    private static Instant timestamp(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) return null;
        if (!node.isIntegralNumber() || node.asLong() <= 0) throw new IllegalArgumentException("timestamp");
        return Instant.ofEpochMilli(node.asLong());
    }

    private static String safeFailure(Exception failure) {
        if (failure instanceof ContractFailure) return failure.getMessage();
        if (failure instanceof ProviderException) return "HTTP 请求失败或供应商冷却中";
        return "响应格式、证券身份或数值校验失败";
    }

    private static final class ContractFailure extends RuntimeException {
        private ContractFailure(String message) { super(message); }
    }
}
