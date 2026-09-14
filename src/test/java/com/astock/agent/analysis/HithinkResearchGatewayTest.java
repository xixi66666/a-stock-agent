package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.*;
import com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient;
import com.astock.agent.technical.*;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.*;
import java.net.URI;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class HithinkResearchGatewayTest {
    private final SecurityId security = SecurityId.parse("600519");
    private final ResearchGateway primary = mock(ResearchGateway.class, invocation -> DataSection.unavailable("offline"));
    private final HithinkFinanceClient hithink = mock(HithinkFinanceClient.class);
    private final Provenance source = new Provenance("HiThink Finance", URI.create("https://fuyao.aicubes.cn/api/a-share/financials/income-statements"),
            null, Instant.now(), false, null);

    @Test void hithinkDividendsRemainAvailableWhenLegacyCapitalFails() {
        var gateway = new HithinkResearchGateway(primary,hithink);
        var events = List.of(new CapitalData.DividendRecord(LocalDate.of(2025,12,31),BigDecimal.TEN,null,null,"event"));
        when(hithink.fetchDividends(security)).thenReturn(DataSection.unverified(events,source,List.of("timestamp missing")));
        var section = gateway.capital(security);
        assertThat(section.payload()).isPresent();
        var capital = (CapitalData) section.payload().orElseThrow();
        assertThat(capital.dividends()).isEqualTo(events);
        assertThat(capital.components().get("dividends").provenance().orElseThrow().provider()).isEqualTo("HiThink Finance");
        assertThat(capital.components().get("legacyCapital").status()).isEqualTo(SectionStatus.UNAVAILABLE);
    }

    @Test void hithinkDragonTigerRecordsRemainAvailableWhenLegacyCapitalFails() {
        var gateway = new HithinkResearchGateway(primary,hithink);
        var records = List.of(new CapitalData.DragonTigerRecord(LocalDate.of(2026,9,11),"偏离值",BigDecimal.TEN,null));
        when(hithink.fetchDividends(security)).thenReturn(DataSection.unavailable("no dividends"));
        when(hithink.fetchDragonTiger(security)).thenReturn(DataSection.unverified(records,source,List.of("turnover missing")));
        var section = (CapitalData) gateway.capital(security).payload().orElseThrow();
        assertThat(section.dragonTigerRecords()).isEqualTo(records);
        assertThat(section.components().get("dragonTiger").provenance().orElseThrow().provider())
                .isEqualTo("HiThink Finance");
        assertThat(section.components().get("dividends").status()).isEqualTo(SectionStatus.UNAVAILABLE);
    }

    @Test void prefersHithinkPricesAndBarsAndFallsBackLocallyOnFailure() {
        var gateway = new HithinkResearchGateway(primary,hithink);
        var quote = new Quote(security,"name",BigDecimal.TEN,null,null,null,null,null,null,null,null,
                null,null,null,null,null,null,null,null,null,null,null);
        var preferred = DataSection.unverified(quote,source,List.of("trade time missing"));
        var bars = DataSection.healthy(List.of(new DailyBar(LocalDate.of(2026,9,11),BigDecimal.TEN,
                BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,null)),source);
        when(hithink.fetchQuote(security)).thenReturn(preferred);
        when(hithink.fetchDailyBars(security)).thenReturn(bars);
        assertThat(gateway.quote(security)).isEqualTo(preferred);
        assertThat(gateway.bars(security)).isEqualTo(bars);
        verify(primary, atMostOnce()).quote(security);
        verify(primary,never()).bars(security);
        var sina = new Provenance("Sina Finance",URI.create("https://quotes.sina.cn/"),null,Instant.now(),false,null);
        when(hithink.fetchStatementHistory(security)).thenReturn(DataSection.unavailable("code=2003"));
        var history = new FinancialStatementHistory(security,List.of(new FinancialPeriodStatement(
                LocalDate.of(2025,12,31),BigDecimal.TEN,null,null,null,null,null,null,null,null,null,null)));
        when(primary.financialHistory(security)).thenReturn(DataSection.healthy(history,sina));
        var fallback = gateway.financialHistory(security);
        assertThat(fallback.status()).isEqualTo(SectionStatus.DEGRADED);
        assertThat(fallback.provenance().orElseThrow().provider()).isEqualTo("Sina Finance");
        assertThat(fallback.provenance().orElseThrow().fallbackProvider()).isEqualTo("HiThink Finance");
    }

    @Test void hithinkFinancialHistoryIsFirstAndCachedWithoutCallingSina() {
        var gateway = new HithinkResearchGateway(primary, hithink);
        var unavailable = DataSection.<FinancialStatementHistory>unavailable("Sina unavailable");
        when(primary.financialHistory(security)).thenReturn(unavailable);
        var period = new FinancialPeriodStatement(LocalDate.of(2025,12,31), BigDecimal.TEN,
                null,null,null,null,null,null,null,null,null,null);
        var history = new FinancialStatementHistory(security,List.of(period));
        when(hithink.fetchStatementHistory(security)).thenReturn(DataSection.degraded(history,source,List.of("annual")));
        var result = gateway.financialHistory(security);
        assertThat(result.status()).isEqualTo(SectionStatus.DEGRADED);
        assertThat(result.provenance().orElseThrow().fallbackProvider()).isNull();
        assertThat(gateway.financialHistory(security).provenance().orElseThrow().cached()).isTrue();
        verify(hithink,times(1)).fetchStatementHistory(security);
        assertThat(gateway.fundamentals(security).provenance().orElseThrow().provider()).isEqualTo("HiThink Finance");
        verify(primary,never()).financialHistory(security);
        verify(primary,never()).fundamentals(security);
    }

    @Test void valuationSurvivesAggregationAndSnapshotCopy() {
        var valuation = DataSection.healthy(new ValuationSnapshot(security,BigDecimal.TEN,null,null,null,null),source);
        when(primary.valuation(security)).thenReturn(valuation);
        when(primary.quote(security)).thenReturn(DataSection.unavailable("no quote"));
        when(primary.bars(security)).thenReturn(DataSection.healthy(List.of(),source));
        when(primary.crossCheckBars(security)).thenReturn(DataSection.unavailable("no cross"));
        var service = new ResearchAggregationService(primary,new TechnicalAnalysisService(new BarSeriesFactory()),
                new DataQualityScorer(),new FundFlowSummaryCalculator(),Caffeine.newBuilder().build());
        var result = service.research(security);
        assertThat(result.valuation()).isEqualTo(valuation);
        assertThat(result.withCrossSourceConsistent(false).valuation()).isEqualTo(valuation);
    }

    @Test void enrichesMissingQuoteFieldsWithoutReplacingPrimaryValues() {
        when(hithink.fetchQuote(security)).thenReturn(DataSection.unverified(
                quote(new BigDecimal("100"), null, null, null), source, List.of("trade time missing")));
        var tencentSource = new Provenance("Tencent", URI.create("https://qt.gtimg.cn/q=sh600519"),
                null, Instant.now(), false, null);
        var tencentQuote = quote(new BigDecimal("999"), new BigDecimal("19.58"),
                new BigDecimal("6.35"), new BigDecimal("0.11"));
        var gateway = new HithinkResearchGateway(primary, hithink, List.of(
                ignored -> DataSection.healthy(tencentQuote, tencentSource)));

        var section = gateway.quote(security);
        assertThat(section.payload().orElseThrow().price()).isEqualByComparingTo("100");
        assertThat(section.payload().orElseThrow().peTtm()).isEqualByComparingTo("19.58");
        assertThat(section.payload().orElseThrow().pb()).isEqualByComparingTo("6.35");
        assertThat(section.payload().orElseThrow().turnoverPercent()).isEqualByComparingTo("0.11");
        assertThat(section.status()).isEqualTo(SectionStatus.UNVERIFIED);
        assertThat(section.provenance().orElseThrow().provider()).isEqualTo("HiThink Finance + Tencent");
        assertThat(section.issues()).anyMatch(s -> s.contains("Tencent") && s.contains("市盈率TTM"));
    }

    @Test void fallsBackWholeSectionThroughOrderedSourcesWhenPrimaryUnavailable() {
        when(hithink.fetchQuote(security)).thenReturn(DataSection.unavailable("code=2003"));
        var eastmoneySource = new Provenance("Eastmoney",
                URI.create("https://push2.eastmoney.com/api/qt/stock/get"), null, Instant.now(), false, null);
        var eastmoneyQuote = quote(new BigDecimal("1275"), new BigDecimal("19.58"),
                new BigDecimal("6.35"), new BigDecimal("0.11"));
        var gateway = new HithinkResearchGateway(primary, hithink, List.of(
                ignored -> DataSection.unavailable("Tencent offline"),
                ignored -> DataSection.healthy(eastmoneyQuote, eastmoneySource)));

        var section = gateway.quote(security);
        assertThat(section.status()).isEqualTo(SectionStatus.DEGRADED);
        assertThat(section.payload().orElseThrow().price()).isEqualByComparingTo("1275");
        assertThat(section.provenance().orElseThrow().provider()).isEqualTo("Eastmoney");
        assertThat(section.provenance().orElseThrow().fallbackProvider()).isEqualTo("HiThink Finance");
        assertThat(section.issues()).anyMatch(s -> s.contains("同花顺"));
    }

    @Test void allQuoteSourcesUnavailableStaysUnavailable() {
        when(hithink.fetchQuote(security)).thenReturn(DataSection.unavailable("code=2003"));
        var gateway = new HithinkResearchGateway(primary, hithink,
                List.of(ignored -> DataSection.unavailable("Tencent offline")));
        var section = gateway.quote(security);
        assertThat(section.status()).isEqualTo(SectionStatus.UNAVAILABLE);
        assertThat(section.issues()).anyMatch(s -> s.contains("Tencent offline"));
    }

    private Quote quote(BigDecimal price, BigDecimal peTtm, BigDecimal pb, BigDecimal turnover) {
        return new Quote(security, "贵州茅台", price, new BigDecimal("99"), new BigDecimal("98"),
                new BigDecimal("101"), new BigDecimal("97"), null, null, null, null,
                turnover, null, null, peTtm, null, pb, null, null, null, null,
                Instant.parse("2026-09-14T01:00:00Z"));
    }
}
