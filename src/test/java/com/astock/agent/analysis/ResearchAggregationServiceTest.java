package com.astock.agent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SectionStatus;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.technical.BarSeriesFactory;
import com.astock.agent.technical.TechnicalAnalysisService;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ResearchAggregationServiceTest {

    @Test
    void newsFailureDoesNotDiscardQuoteOrTechnicalData() {
        ResearchGateway gateway = new StubGateway();
        ResearchAggregationService service = new ResearchAggregationService(
                gateway,
                new TechnicalAnalysisService(new BarSeriesFactory()),
                new DataQualityScorer(),
                Caffeine.newBuilder().maximumSize(10).build());

        StockResearchSnapshot result = service.research(SecurityId.parse("600519"));

        assertThat(result.quote().status()).isEqualTo(SectionStatus.HEALTHY);
        assertThat(result.technical().status()).isEqualTo(SectionStatus.HEALTHY);
        assertThat(result.news().status()).isEqualTo(SectionStatus.UNAVAILABLE);
        assertThat(result.news().issues()).contains("simulated provider block");
    }

    private static final class StubGateway implements ResearchGateway {
        private final SecurityId id = SecurityId.parse("600519");
        private final Provenance source = new Provenance("fixture", URI.create("https://example.com"), null,
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
        @Override public DataSection<?> fundFlow(SecurityId security) { return DataSection.healthy(List.of(), source); }
        @Override public DataSection<?> capital(SecurityId security) { return DataSection.healthy(List.of(), source); }
        @Override public DataSection<?> fundamentals(SecurityId security) { return DataSection.healthy(List.of(), source); }
        @Override public DataSection<?> research(SecurityId security) { return DataSection.healthy(List.of(), source); }
        @Override public DataSection<?> news(SecurityId security) { return DataSection.unavailable("simulated provider block"); }
        @Override public DataSection<?> announcements(SecurityId security) { return DataSection.healthy(List.of(), source); }

        private static BigDecimal bd(double value) { return BigDecimal.valueOf(value); }
    }
}
