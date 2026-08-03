package com.astock.agent.analysis.institutional;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.agent.report.ReportFact;
import com.astock.agent.marketdata.model.CapitalData;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.FundFlow;
import com.astock.agent.marketdata.model.FundamentalData;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.ResearchItem;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.technical.IndicatorCard;
import com.astock.agent.technical.IndicatorState;
import com.astock.agent.technical.TechnicalSnapshot;
import com.astock.agent.technical.Timeframe;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchJudgementEngineTest {

    private final ResearchJudgementEngine engine = new ResearchJudgementEngine();

    @Test
    void requiresQuoteBarsAndThreeUsableDimensionsForDirection() {
        DeterministicAssessment result = engine.assess(snapshotWithOnlyQuoteAndBars());

        assertThat(result.direction()).isEqualTo(Direction.INSUFFICIENT);
        assertThat(result.evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void keepsBullishTrendAndNegativeFlowAsExplicitConflict() {
        DeterministicAssessment result = engine.assess(snapshotWithStrongTrendAndOutflow());

        assertThat(result.conflicts()).anyMatch(text -> text.contains("趋势") && text.contains("资金"));
        assertThat(result.invalidationConditions()).isNotEmpty();
    }

    @Test
    void missingValuationDoesNotReweightRemainingDimensions() {
        DeterministicAssessment complete = engine.assess(completeSnapshot(true));
        DeterministicAssessment missing = engine.assess(completeSnapshot(false));

        assertThat(missing.internalScore()).isEqualTo(
                complete.internalScore() - complete.dimension("VALUATION_INDUSTRY").weightedContribution());
        assertThat(missing.evidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
    }

    @Test
    void explainsTechnicalTrendWithFactsMethodAndCounterEvidence() {
        ModuleAnalysis result = engine.assess(snapshotWithStrongTrendAndOutflow())
                .moduleAnalysis(AnalysisModule.TECHNICAL_PRICE_VOLUME);

        assertThat(result.conclusion()).contains("SMA20").contains("SMA60");
        assertThat(result.facts()).extracting(ReportFact::label)
                .contains("SMA20", "SMA60", "20日收益", "20日量比");
        assertThat(result.methodology())
                .anyMatch(value -> value.contains("趋势") && value.contains("动量"));
        assertThat(result.sourceIds()).contains("technical");
    }

    @Test
    void explainsValuationPremiumAndComparabilityLimit() {
        ModuleAnalysis result = engine.assess(completeSnapshot(true))
                .moduleAnalysis(AnalysisModule.VALUATION_INDUSTRY);

        assertThat(result.facts()).extracting(ReportFact::label)
                .contains("个股PE(TTM)", "行业PE中位数", "PE相对溢价");
        assertThat(result.methodology()).anyMatch(value -> value.contains("相对估值"));
        assertThat(result.limitations()).anyMatch(value -> value.contains("可比"));
    }

    @Test
    void doesNotTreatCapitalRecordCountsAsDirectionalEvidence() {
        ModuleAnalysis result = engine.assess(snapshotWithCapitalCountsOnly())
                .moduleAnalysis(AnalysisModule.FUND_FLOW_CAPITAL);

        assertThat(result.direction()).isEqualTo(Direction.INSUFFICIENT);
        assertThat(result.limitations())
                .anyMatch(value -> value.contains("规模") || value.contains("变化"));
    }

    @Test
    void coreDriversAreConcreteSignalsWithoutInternalScoresOrPlaceholders() throws Exception {
        DeterministicAssessment result = engine.assess(completeSnapshot(true));

        assertThat(result.coreDrivers()).isNotEmpty();
        assertThat(result.coreDrivers()).allSatisfy(driver -> {
            assertThat(driver.conclusion()).isNotBlank();
            assertThat(driver.rationale()).isNotBlank();
            assertThat(driver.factIds()).isNotEmpty();
        });
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(result.coreDrivers()))
                .doesNotContain("规则方向分", "共同判断", "internalScore");
    }

    private static StockResearchSnapshot snapshotWithOnlyQuoteAndBars() {
        return base(DataSection.unavailable("technical missing"), DataSection.unavailable("flow missing"),
                DataSection.unavailable("events missing"), DataSection.unavailable("fundamentals missing"),
                DataSection.unavailable("valuation missing"), DataSection.unavailable("research missing"));
    }

    private static StockResearchSnapshot snapshotWithStrongTrendAndOutflow() {
        TechnicalSnapshot technical = new TechnicalSnapshot(Timeframe.DAILY, LocalDate.of(2026, 7, 30), List.of(
                card("SMA_20", 100, IndicatorState.STRONG), card("SMA_60", 98, IndicatorState.STRONG),
                card("SMA_120", 95, IndicatorState.STRONG), card("MACD_12_26_9", 1, IndicatorState.STRONG),
                card("RETURN_20", 8, IndicatorState.STRONG), card("RETURN_60", 12, IndicatorState.STRONG),
                card("VOLUME_RATIO_20", 1.4, IndicatorState.STRONG), card("NATR_14", 2, IndicatorState.CONTRACTING),
                card("MAX_DRAWDOWN", -4, IndicatorState.NEUTRAL)));
        return base(DataSection.healthy(technical, SOURCE),
                DataSection.healthy(List.of(new FundFlow(LocalDate.of(2026, 7, 30), bd(-100), bd(0), bd(0), bd(0), bd(0), "fixture")), SOURCE),
                DataSection.healthy(List.of(new com.astock.agent.marketdata.model.Announcement("业绩预增", "业绩预告", LocalDate.of(2026, 7, 20), "https://example.com/a")), SOURCE),
                DataSection.unavailable("fundamentals missing"), DataSection.unavailable("valuation missing"),
                DataSection.unavailable("research missing"));
    }

    private static StockResearchSnapshot snapshotWithCapitalCountsOnly() {
        StockResearchSnapshot base = snapshotWithOnlyQuoteAndBars();
        CapitalData capital = new CapitalData(
                List.of(new CapitalData.MarginRecord(LocalDate.of(2026, 7, 30), null, null, null, null)),
                List.of(new CapitalData.BlockTrade(LocalDate.of(2026, 7, 30), null, null,
                        null, null, null, "", "")),
                List.of(), List.of(), List.of(), List.of());
        return new StockResearchSnapshot(base.security(), base.quote(), base.bars(), base.technical(),
                base.sectors(), base.industryValuation(), base.fundFlow(), DataSection.healthy(capital, SOURCE),
                base.fundamentals(), base.research(), base.news(), base.announcements(), base.quality(),
                base.crossSourceConsistent(), base.coreCompleteness(), base.authoritativeSources(), base.fetchedAt());
    }

    private static StockResearchSnapshot completeSnapshot(boolean valuationAvailable) {
        TechnicalSnapshot technical = new TechnicalSnapshot(Timeframe.DAILY, LocalDate.of(2026, 7, 30), List.of(
                card("SMA_20", 100, IndicatorState.STRONG), card("SMA_60", 98, IndicatorState.STRONG),
                card("SMA_120", 95, IndicatorState.STRONG), card("MACD_12_26_9", 1, IndicatorState.STRONG),
                card("RETURN_20", 8, IndicatorState.STRONG), card("RETURN_60", 12, IndicatorState.STRONG),
                card("VOLUME_RATIO_20", 1.4, IndicatorState.STRONG), card("NATR_14", 2, IndicatorState.CONTRACTING),
                card("MAX_DRAWDOWN", -4, IndicatorState.NEUTRAL)));
        DataSection<IndustryValuationData> valuation = valuationAvailable
                ? DataSection.healthy(new IndustryValuationData("BK", "白酒", 10, 10, 0, 10, 0,
                        bd(20), bd(30), bd(25), bd(3), bd(4), bd(20)), SOURCE)
                : DataSection.unavailable("valuation missing");
        return base(DataSection.healthy(technical, SOURCE),
                DataSection.healthy(List.of(new FundFlow(LocalDate.of(2026, 7, 30), bd(100), bd(0), bd(0), bd(0), bd(0), "fixture")), SOURCE),
                DataSection.healthy(List.of(new com.astock.agent.marketdata.model.Announcement("业绩预增", "业绩预告", LocalDate.of(2026, 7, 20), "https://example.com/a")), SOURCE),
                DataSection.healthy(new FundamentalData(LocalDate.of(2026, 6, 30), Map.of("营业收入", bd(100), "净利润", bd(20)), Map.of("营业收入", bd(12), "净利润", bd(15))), SOURCE),
                valuation,
                DataSection.healthy(List.of(new ResearchItem("r1", "机构A", "增持", LocalDate.of(2026, 7, 1), "https://example.com/r1", bd(5), bd(6)),
                        new ResearchItem("r2", "机构B", "买入", LocalDate.of(2026, 7, 2), "https://example.com/r2", bd(5.5), bd(6.5))), SOURCE));
    }

    private static StockResearchSnapshot base(DataSection<com.astock.agent.technical.TechnicalSnapshot> technical, DataSection<?> flow,
            DataSection<?> events, DataSection<?> fundamentals, DataSection<IndustryValuationData> valuation, DataSection<?> research) {
        SecurityId id = SecurityId.parse("600519");
        Quote quote = new Quote(id, "贵州茅台", bd(100), bd(99), bd(99), bd(101), bd(98), bd(1), bd(1), bd(1000),
                bd(100000), bd(1), bd(2), bd(1), bd(20), bd(20), bd(5), bd(1000000), bd(900000), bd(110), bd(90), Instant.now());
        List<DailyBar> bars = List.of(new DailyBar(LocalDate.of(2026, 7, 30), bd(99), bd(101), bd(98), bd(100), bd(1000), bd(100000)));
        return new StockResearchSnapshot(id, DataSection.healthy(quote, SOURCE), DataSection.healthy(bars, SOURCE),
                technical, DataSection.unavailable("sectors missing"),
                valuation, flow, DataSection.unavailable("capital missing"), fundamentals,
                research, DataSection.unavailable("news unavailable"),
                events, null, true, true, true, Instant.now());
    }

    private static IndicatorCard card(String id, double value, IndicatorState state) {
        return new IndicatorCard(id, "fixture", id, Map.of(), value, "", state, "fixture trigger", List.of(value),
                LocalDate.of(2026, 7, 30), com.astock.agent.marketdata.model.SectionStatus.HEALTHY);
    }

    private static BigDecimal bd(double value) { return BigDecimal.valueOf(value); }

    private static final Provenance SOURCE = new Provenance("fixture", URI.create("https://example.com"), null,
            Instant.parse("2026-07-30T08:00:00Z"), false, null);
}
