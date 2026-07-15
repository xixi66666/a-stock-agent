package com.astock.agent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataQualityScorerTest {

    @Test
    void scoreIsTransparentSumOfApprovedWeights() {
        DataQualityScorer scorer = new DataQualityScorer();

        DataQualityBreakdown score = scorer.score(completeSnapshot());

        assertThat(score.freshness()).isEqualTo(30);
        assertThat(score.consistency()).isEqualTo(30);
        assertThat(score.completeness()).isEqualTo(25);
        assertThat(score.authority()).isEqualTo(15);
        assertThat(score.total()).isEqualTo(100);
    }

    static StockResearchSnapshot completeSnapshot() {
        SecurityId id = SecurityId.parse("600519");
        Provenance source = new Provenance("Tencent Finance", URI.create("https://qt.gtimg.cn/q=sh600519"),
                Instant.parse("2026-07-15T08:15:00Z"), Instant.parse("2026-07-15T08:15:01Z"), false, null);
        Quote quote = new Quote(id, "贵州茅台", bd("1251.06"), bd("1214.88"), bd("1203.66"),
                bd("1256.60"), bd("1198.66"), bd("36.18"), bd("2.98"), bd("7194400"),
                bd("8922860000"), bd("0.58"), bd("4.77"), bd("1.82"), bd("18.91"), bd("14.35"),
                bd("6.72"), bd("1563927000000"), bd("1563927000000"), bd("1336.37"), bd("1093.39"),
                Instant.parse("2026-07-15T08:15:00Z"));
        DailyBar bar = new DailyBar(LocalDate.parse("2026-07-15"), bd("1203.66"), bd("1256.60"),
                bd("1198.66"), bd("1251.06"), bd("7194371"), bd("8922861367"));
        return StockResearchSnapshot.empty(id)
                .withQuote(DataSection.healthy(quote, source))
                .withBars(DataSection.healthy(List.of(bar), source))
                .withCrossSourceConsistent(true)
                .withCoreCompleteness(true)
                .withAuthoritativeSources(true);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
