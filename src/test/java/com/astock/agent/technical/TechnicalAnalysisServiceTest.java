package com.astock.agent.technical;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.DailyBar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TechnicalAnalysisServiceTest {

    private final TechnicalAnalysisService service = new TechnicalAnalysisService(new BarSeriesFactory());

    @Test
    void calculatesCanonicalRsiAndMacdWithExplainableCards() {
        TechnicalSnapshot result = service.analyze(series(320), Timeframe.DAILY);

        assertThat(result.card("RSI_12").value()).isBetween(0.0, 100.0);
        assertThat(result.card("MACD_12_26_9").parameters())
                .containsEntry("fast", 12)
                .containsEntry("slow", 26)
                .containsEntry("signal", 9);
        assertThat(result.cards()).hasSizeGreaterThanOrEqualTo(24);
        assertThat(result.cards()).allMatch(card -> card.trigger() != null && !card.trigger().isBlank());
        assertThat(result.cards()).allMatch(card -> card.calculatedAt() != null);
    }

    @Test
    void calculatesTrendMomentumVolatilityVolumeAndRiskGroups() {
        TechnicalSnapshot result = service.analyze(series(320), Timeframe.DAILY);

        assertThat(result.cards()).extracting(IndicatorCard::group)
                .contains("趋势", "动量", "波动", "量价", "相对强弱与风险");
        assertThat(result.card("SMA_20").series()).isNotEmpty();
        assertThat(result.card("MAX_DRAWDOWN").value()).isLessThanOrEqualTo(0.0);
    }

    private static List<DailyBar> series(int count) {
        return IntStream.range(0, count).mapToObj(index -> {
            double close = 100 + index * 0.08 + Math.sin(index / 7.0) * 3;
            double open = close - Math.sin(index / 3.0);
            double high = Math.max(open, close) + 1.2;
            double low = Math.min(open, close) - 1.1;
            double volume = 1_000_000 + (index % 17) * 20_000;
            return new DailyBar(
                    LocalDate.of(2025, 1, 1).plusDays(index), value(open), value(high), value(low),
                    value(close), value(volume), value(close * volume));
        }).toList();
    }

    private static BigDecimal value(double value) {
        return BigDecimal.valueOf(value);
    }
}
