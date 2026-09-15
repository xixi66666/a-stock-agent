package com.astock.agent.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.FundFlow;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SectionStatus;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.technical.BarSeriesFactory;
import com.astock.agent.technical.TechnicalAnalysisService;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ResearchAggregationServiceTest {

    @Test void recognizesOfficialHithinkAndPreservesStaleStatusInDerivedTechnicalData() {
        var official = new Provenance("HiThink Finance",URI.create("https://fuyao.aicubes.cn/"),null,Instant.now(),false,null);
        ResearchGateway gateway = new StubGateway() {
            @Override public DataSection<Quote> quote(SecurityId security) {
                return DataSection.unverified(super.quote(security).payload().orElseThrow(),official,List.of("成交时间缺失"));
            }
            @Override public DataSection<List<DailyBar>> bars(SecurityId security) {
                return DataSection.stale(super.bars(security).payload().orElseThrow(),official,List.of("陈旧日线"));
            }
            @Override public DataSection<List<DailyBar>> crossCheckBars(SecurityId security) {
                return DataSection.unavailable("offline");
            }
        };
        var result = service(gateway).research(SecurityId.parse("600519"));
        assertThat(result.authoritativeSources()).isTrue();
        assertThat(result.technical().status()).isEqualTo(SectionStatus.STALE);
        assertThat(result.technical().issues()).contains("陈旧日线");
    }

    @Test
    void newsFailureDoesNotDiscardQuoteOrTechnicalData() {
        ResearchGateway gateway = new StubGateway();
        ResearchAggregationService service = service(gateway);

        StockResearchSnapshot result = service.research(SecurityId.parse("600519"));

        assertThat(result.quote().status()).isEqualTo(SectionStatus.HEALTHY);
        assertThat(result.technical().status()).isEqualTo(SectionStatus.HEALTHY);
        assertThat(result.news().status()).isEqualTo(SectionStatus.UNAVAILABLE);
        assertThat(result.news().issues()).contains("simulated provider block");
    }

    @Test
    void industryValuationFailureDoesNotDiscardQuoteOrTechnicalData() {
        ResearchGateway gateway = new StubGateway() {
            @Override
            public DataSection<IndustryValuationData> industryValuation(SecurityId security) {
                return DataSection.unavailable("peer batch unavailable");
            }
        };

        StockResearchSnapshot result = service(gateway).research(SecurityId.parse("600519"));

        assertThat(result.quote().status()).isEqualTo(SectionStatus.HEALTHY);
        assertThat(result.technical().status()).isEqualTo(SectionStatus.HEALTHY);
        assertThat(result.industryValuation().status()).isEqualTo(SectionStatus.UNAVAILABLE);
    }

    @Test
    void fundFlowSummaryPreservesFallbackStatusAndProvenance() {
        ResearchGateway gateway = new StubGateway() {
            @Override
            public DataSection<?> fundFlow(SecurityId security) {
                Provenance fallback = new Provenance(
                        "Sina", URI.create("https://example.com/fund-flow"), null,
                        Instant.parse("2026-07-15T08:00:00Z"), false, "Eastmoney");
                return DataSection.degraded(List.of(
                        new FundFlow(LocalDate.of(2026, 7, 15), bd(10), bd(1), bd(2), bd(3), bd(4), "Sina")),
                        fallback, List.of("Eastmoney unavailable; using Sina daily fund-flow fallback"));
            }
        };

        StockResearchSnapshot result = service(gateway).research(SecurityId.parse("600519"));

        assertThat(result.fundFlowSummary().status()).isEqualTo(SectionStatus.DEGRADED);
        assertThat(result.fundFlowSummary().payload().orElseThrow().latestDay().mainNetYuan())
                .isEqualByComparingTo("10");
        assertThat(result.fundFlowSummary().provenance().orElseThrow().fallbackProvider())
                .isEqualTo("Eastmoney");
    }

    @Test
    void emptySuccessAndProviderFailureRemainDifferentStates() {
        StockResearchSnapshot emptyResult = service(new StubGateway())
                .research(SecurityId.parse("600519"));
        StockResearchSnapshot failedResult = service(new StubGateway() {
            @Override
            public DataSection<?> fundFlow(SecurityId security) {
                return DataSection.unavailable("provider failed");
            }
        }).research(SecurityId.parse("600519"));

        assertThat(emptyResult.fundFlowSummary().status()).isEqualTo(SectionStatus.DEGRADED);
        assertThat(emptyResult.fundFlowSummary().payload().orElseThrow().latestDay().sampleDays()).isZero();
        assertThat(emptyResult.fundFlowSummary().issues())
                .contains("Fund-flow provider returned an empty history");

        assertThat(failedResult.fundFlowSummary().status()).isEqualTo(SectionStatus.UNAVAILABLE);
        assertThat(failedResult.fundFlowSummary().payload()).isEmpty();
        assertThat(failedResult.fundFlowSummary().issues()).contains("provider failed");
    }

    @Test
    void usesFresherIndependentBarsWhenPrimaryKlineIsStale() {
        ResearchGateway gateway = new StubGateway() {
            @Override
            public DataSection<List<DailyBar>> bars(SecurityId security) {
                Provenance tencent = new Provenance("Tencent", URI.create("https://example.com/tencent-bars"), null,
                        Instant.parse("2026-09-08T08:00:00Z"), false, null);
                return DataSection.healthy(
                        barsEndingOn(LocalDate.of(2026, 9, 3)), tencent);
            }

            @Override
            public DataSection<List<DailyBar>> crossCheckBars(SecurityId security) {
                Provenance baidu = new Provenance("Baidu", URI.create("https://example.com/baidu-bars"), null,
                        Instant.parse("2026-09-08T08:00:00Z"), false, null);
                return DataSection.healthy(barsEndingOn(LocalDate.of(2026, 9, 8)), baidu);
            }
        };

        StockResearchSnapshot result = service(gateway).research(SecurityId.parse("600519"));

        assertThat(result.bars().payload().orElseThrow().getLast().date())
                .isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(result.bars().status()).isEqualTo(SectionStatus.DEGRADED);
        assertThat(result.bars().provenance().orElseThrow().provider()).isEqualTo("Baidu");
        assertThat(result.bars().provenance().orElseThrow().fallbackProvider()).isEqualTo("Tencent");
        assertThat(result.bars().issues()).anyMatch(issue -> issue.contains("fresher"));
        assertThat(result.technical().payload().orElseThrow().calculatedAt())
                .isEqualTo(LocalDate.of(2026, 9, 8));
    }

    @Test
    void stalledSectionDegradesLocallyInsteadOfHangingTheWholeSnapshot() {
        ResearchGateway gateway = new StubGateway() {
            @Override
            public DataSection<?> announcements(SecurityId security) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return DataSection.healthy(List.of(), source);
            }
        };

        StockResearchSnapshot result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> service(gateway, Duration.ofMillis(200)).research(SecurityId.parse("600519")));

        assertThat(result.quote().status()).isEqualTo(SectionStatus.HEALTHY);
        assertThat(result.announcements().status()).isEqualTo(SectionStatus.UNAVAILABLE);
        assertThat(result.announcements().issues()).anyMatch(issue -> issue.contains("timed out"));
    }

    private static ResearchAggregationService service(ResearchGateway gateway) {
        return new ResearchAggregationService(
                gateway,
                new TechnicalAnalysisService(new BarSeriesFactory()),
                new DataQualityScorer(),
                new FundFlowSummaryCalculator(),
                Caffeine.newBuilder().maximumSize(10).build());
    }

    private static ResearchAggregationService service(ResearchGateway gateway, Duration sectionTimeout) {
        return new ResearchAggregationService(
                gateway,
                new TechnicalAnalysisService(new BarSeriesFactory()),
                new DataQualityScorer(),
                new FundFlowSummaryCalculator(),
                Caffeine.newBuilder().maximumSize(10).build(),
                sectionTimeout);
    }

    private static class StubGateway implements ResearchGateway {
        private final SecurityId id = SecurityId.parse("600519");
        protected final Provenance source = new Provenance("fixture", URI.create("https://example.com"), null,
                Instant.parse("2026-07-15T08:00:00Z"), false, null);

        @Override
        public DataSection<Quote> quote(SecurityId security) {
            return DataSection.healthy(new Quote(id, "贵州茅台", bd(120), bd(119), bd(119), bd(121), bd(118),
                    bd(1), bd(0.84), bd(1000), bd(120000), bd(1), bd(2), bd(1), bd(20), bd(20), bd(5),
                    bd(1000000), bd(900000), bd(130), bd(110), Instant.parse("2026-07-15T08:00:00Z")), source);
        }

        @Override
        public DataSection<List<DailyBar>> bars(SecurityId security) {
            List<DailyBar> bars = IntStream.range(0, 320).mapToObj(index -> new DailyBar(
                    LocalDate.of(2025, 1, 1).plusDays(index), bd(100 + index * 0.1), bd(102 + index * 0.1),
                    bd(99 + index * 0.1), bd(101 + index * 0.1), bd(1000000), bd(100000000))).toList();
            return DataSection.healthy(bars, source);
        }

        @Override public DataSection<List<DailyBar>> crossCheckBars(SecurityId security) { return bars(security); }
        @Override public DataSection<?> sectors(SecurityId security) { return DataSection.healthy(List.of(), source); }
        @Override public DataSection<IndustryValuationData> industryValuation(SecurityId security) {
            return DataSection.unavailable("not provided by fixture");
        }
        @Override public DataSection<?> fundFlow(SecurityId security) { return DataSection.healthy(List.of(), source); }
        @Override public DataSection<?> capital(SecurityId security) { return DataSection.healthy(List.of(), source); }
        @Override public DataSection<?> fundamentals(SecurityId security) { return DataSection.healthy(List.of(), source); }
        @Override
        public DataSection<FinancialStatementHistory> financialHistory(SecurityId security) {
            return DataSection.unavailable("not stubbed");
        }
        @Override public DataSection<?> research(SecurityId security) { return DataSection.healthy(List.of(), source); }
        @Override public DataSection<?> news(SecurityId security) { return DataSection.unavailable("simulated provider block"); }
        @Override public DataSection<?> announcements(SecurityId security) { return DataSection.healthy(List.of(), source); }

        protected static BigDecimal bd(double value) { return BigDecimal.valueOf(value); }

        protected static List<DailyBar> barsEndingOn(LocalDate end) {
            return IntStream.range(0, 320).mapToObj(index -> {
                double close = 100 + index * 0.1;
                return new DailyBar(end.minusDays(319L - index), bd(close - 0.1), bd(close + 0.8),
                        bd(close - 0.8), bd(close), bd(1_000_000), bd(close * 1_000_000));
            }).toList();
        }
    }
}
