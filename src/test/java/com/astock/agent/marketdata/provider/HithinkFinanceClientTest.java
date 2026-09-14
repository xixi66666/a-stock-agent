package com.astock.agent.marketdata.provider;

import com.astock.agent.marketdata.model.*;
import com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;

class HithinkFinanceClientTest {
    private HttpServer server;
    private HithinkFinanceClient client;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("X-api-key"));
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

    @Test void dividendEventsPreservePerShareUnitsAndEmptySuccess() {
        body.set("""
                {"code":0,"data":{"thscode":"600519.SH","ticker":"600519","item":[
                {"ticker":"600519","ex_date_ms":1767110400000,"dividend_per_share":3.25,"per_share_bonus":0.1}]}}
                """);
        var result = client.fetchDividends(SecurityId.parse("600519"));
        var event = result.payload().orElseThrow().getFirst();
        assertThat(event.cashPerShareYuan()).isEqualByComparingTo("3.25");
        assertThat(event.bonusPerTenShares()).isEqualByComparingTo("1");
        assertThat(event.transferPerTenShares()).isNull();
        body.set("{\"code\":0,\"data\":{\"thscode\":\"600519.SH\",\"ticker\":\"600519\",\"item\":[]}}");
        assertThat(client.fetchDividends(SecurityId.parse("600519")).payload()).hasValue(java.util.List.of());
    }

    @Test void dailyHistoryIsForwardAdjustedValidatedAndBoundedForStocksAndBenchmarks() {
        var rows = new java.util.ArrayList<String>();
        for (int i=0;i<530;i++) {
            long date = LocalDate.of(2025,1,1).plusDays(i).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            rows.add("{\"date_ms\":"+date+",\"open_price\":10,\"high_price\":12,\"low_price\":9,\"close_price\":11,\"volume\":12345,\"turnover\":135795}");
        }
        body.set("{\"code\":0,\"data\":{\"timestamp\":1789081200000,\"item\":["+String.join(",",rows)+"]}}");
        var result = client.fetchDailyBars(SecurityId.parse("600519"));
        assertThat(result.payload().orElseThrow()).hasSize(520);
        assertThat(result.payload().orElseThrow().getFirst().volumeShares()).isEqualByComparingTo("12345");
        assertThat(result.provenance().orElseThrow().sourceUrl().getQuery()).contains("adjust=forward");
        assertThat(client.fetchBenchmarkBars(BenchmarkId.CSI_300).provenance().orElseThrow().sourceUrl().getQuery())
                .contains("thscode=000300.SH").doesNotContain("adjust");
        body.set(body.get().replace("\"high_price\":12", "\"high_price\":8"));
        assertThat(client.fetchDailyBars(SecurityId.parse("600519")).status()).isEqualTo(SectionStatus.UNAVAILABLE);
    }

    @Test void quoteUsesShareAndYuanUnitsWithoutInventingTradeTimeOrMissingFields() {
        body.set("""
                {"code":0,"data":{"timestamp":1789081200000,"item":[
                {"thscode":"600519.SH","ticker":"600519","name":"贵州茅台","currency":"CNY",
                "asset_type":"a-share","last_price":102,"prev_price":100,"open_price":101,
                "high_price":103,"low_price":100,"price_change":2,"price_change_ratio_pct":2,
                "volume":12345,"turnover":1250000}]}}
                """);
        var section = client.fetchQuote(SecurityId.parse("600519"));
        var quote = section.payload().orElseThrow();
        assertThat(quote.name()).isEqualTo("贵州茅台");
        assertThat(quote.volumeShares()).isEqualByComparingTo("12345");
        assertThat(quote.amountYuan()).isEqualByComparingTo("1250000");
        assertThat(quote.quotedAt()).isNull();
        assertThat(quote.turnoverPercent()).isNull();
        assertThat(quote.totalMarketValueYuan()).isNull();
        assertThat(section.status()).isEqualTo(SectionStatus.UNVERIFIED);
        body.set(body.get().replace("\"high_price\":103", "\"high_price\":99"));
        assertThat(client.fetchQuote(SecurityId.parse("600519")).status()).isEqualTo(SectionStatus.UNAVAILABLE);
    }

    @Test void dragonTigerListFiltersRequestedSecurityAndKeepsUnavailableTurnoverMissing() {
        body.set("""
                {"code":0,"data":{"timestamp":1789081200000,"trade_date":"2026-09-11",
                "stock_items":[
                {"thscode":"000001.SZ","ticker":"000001","name":"平安银行","net_value":10},
                {"thscode":"600519.SH","ticker":"600519","name":"贵州茅台","net_value":123456.78,
                 "limit_reason":"日涨幅偏离值达到7%"}],"hot_money_items":[]}}
                """);
        var section = client.fetchDragonTiger(SecurityId.parse("600519"));
        var record = section.payload().orElseThrow().getFirst();
        assertThat(record.date()).isEqualTo(LocalDate.of(2026,9,11));
        assertThat(record.netBuyYuan()).isEqualByComparingTo("123456.78");
        assertThat(record.turnoverPercent()).isNull();
        assertThat(record.reason()).contains("日涨幅");
    }

    @Test void annualStatementsAlignByDateAndKeepUnsupportedFieldsMissing() {
        body.set("""
                {"code":0,"data":{"timestamp":1767110400000,"item":[
                {"thscode":"600519.SH","ticker":"600519","period":"annual","currency":"CNY",
                "period_end_ms":1767110400000,"report_date_ms":1776355200000,
                "operating_income":168838102514.79,"operating_costs":14892277570.91,
                "net_profit":85310324833.67,"parent_holder_net_profit":82320067101.68,
                "act_cash_flow_net":61522204989.35,"assets_total":303834844021.44,
                "total_debt":49875590112.37,"total_current_assets":252518662398.57,
                "holder_equity_total":253959253909.07}]}}
                """);
        var section = client.fetchStatementHistory(SecurityId.parse("600519"));
        assertThat(section.status()).isEqualTo(SectionStatus.DEGRADED);
        var period = section.payload().orElseThrow().periods().getFirst();
        assertThat(period.reportPeriod()).isEqualTo(LocalDate.parse("2025-12-31"));
        assertThat(period.operatingRevenue()).isEqualByComparingTo("168838102514.79");
        assertThat(period.equityAttributable()).isNull();
        assertThat(period.currentLiabilities()).isNull();
        assertThat(period.shareCapital()).isNull();
        assertThat(section.issues()).anyMatch(s -> s.contains("年报"));
        assertThat(section.issues()).anyMatch(s -> s.contains("2026-04-17"));
    }

    @Test void malformedIncomeDoesNotDiscardValidBalanceAndCashTables() {
        body.set("""
                {"code":0,"data":{"timestamp":1767110400000,"item":[
                {"thscode":"600519.SH","ticker":"600519","period":"annual","currency":"CNY",
                "period_end_ms":1767110400000,"report_date_ms":1776355200000,
                "operating_income":"invalid","assets_total":100,"act_cash_flow_net":20}]}}
                """);
        var section = client.fetchStatementHistory(SecurityId.parse("600519"));
        assertThat(section.payload()).isPresent();
        var latest = section.payload().orElseThrow().periods().getLast();
        assertThat(latest.operatingRevenue()).isNull();
        assertThat(latest.totalAssets()).isEqualByComparingTo("100");
        assertThat(latest.operatingCashFlow()).isEqualByComparingTo("20");
        assertThat(section.issues()).anyMatch(s -> s.contains("income-statements") && s.contains("校验失败"));
    }

    @Test void distinguishesBusinessFailureEmptySuccessMissingTimeAndStaleData() {
        body.set("{\"code\":2003,\"message\":\"test-only-key\",\"data\":null}");
        var failure = client.fetchValuation(SecurityId.parse("600519"));
        assertThat(failure.status()).isEqualTo(SectionStatus.UNAVAILABLE);
        assertThat(failure.issues()).anyMatch(s -> s.contains("2003"));
        assertThat(failure.toString()).doesNotContain("test-only-key");
        body.set("{\"code\":0,\"data\":{\"item\":[],\"timestamp\":null}}");
        assertThat(client.fetchValuation(SecurityId.parse("600519")).issues())
                .anyMatch(s -> s.contains("空结果"));
        body.set("{\"code\":0,\"data\":{\"item\":[{\"thscode\":\"600519.SH\",\"ticker\":\"600519\",\"pe_ttm\":2}],\"timestamp\":null}}");
        assertThat(client.fetchValuation(SecurityId.parse("600519")).status()).isEqualTo(SectionStatus.UNVERIFIED);
        body.set(body.get().replace("\"timestamp\":null", "\"timestamp\":1609459200000"));
        assertThat(client.fetchValuation(SecurityId.parse("600519")).status()).isEqualTo(SectionStatus.STALE);
    }

    @Test void preservesDecimalPrecisionAndDoesNotDeclareEmptyMetricsHealthy() {
        body.set("""
                {"code":0,"data":{"timestamp":1789081200000,"item":[
                {"thscode":"600519.SH","ticker":"600519","pe_ttm":123456789.123456789}]}}
                """);
        assertThat(client.fetchValuation(SecurityId.parse("600519")).payload().orElseThrow().peTtm())
                .isEqualByComparingTo("123456789.123456789");
        body.set(body.get().replace("123456789.123456789", "null"));
        assertThat(client.fetchValuation(SecurityId.parse("600519")).status()).isEqualTo(SectionStatus.UNVERIFIED);
    }

    @Test void valuationPreservesNegativeAndNullValuesWithProvenance() {
        body.set("""
                {"code":0,"data":{"timestamp":1789081200000,"total":1,"item":[
                {"thscode":"600519.SH","ticker":"600519","pe_ttm":-3.2,"pe_mrq":null,
                "pb_mrq":2.5,"ps_ttm":4,"pcf_ttm":-1}]}}
                """);
        var section = client.fetchValuation(SecurityId.parse("600519"));
        assertThat(section.status()).isEqualTo(SectionStatus.HEALTHY);
        assertThat(section.payload().orElseThrow().peTtm()).isEqualByComparingTo("-3.2");
        assertThat(section.payload().orElseThrow().peMrq()).isNull();
        assertThat(section.provenance().orElseThrow().provider()).isEqualTo("HiThink Finance");
        assertThat(section.provenance().orElseThrow().sourceUrl().toString()).doesNotContain("test-only-key");
        assertThat(auth.get()).isEqualTo("test-only-key");
    }

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
}
