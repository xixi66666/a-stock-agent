package com.astock.agent.technical;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.DailyBar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BarSeriesFactoryTest {

    private final BarSeriesFactory factory = new BarSeriesFactory();

    @Test
    void weeklyAggregationUsesLastCloseAndSummedVolume() {
        List<DailyBar> daily = IntStream.range(0, 5)
                .mapToObj(index -> bar(LocalDate.of(2026, 7, 6).plusDays(index), 100 + index, 1_000))
                .toList();

        List<DailyBar> weekly = factory.aggregate(daily, Timeframe.WEEKLY);

        assertThat(weekly).hasSize(1);
        assertThat(weekly.getFirst().open()).isEqualByComparingTo("100");
        assertThat(weekly.getFirst().close()).isEqualByComparingTo("104");
        assertThat(weekly.getFirst().high()).isEqualByComparingTo("105");
        assertThat(weekly.getFirst().volumeShares()).isEqualByComparingTo("5000");
    }

    @Test
    void createsTa4jSeriesWithoutChangingBarCount() {
        List<DailyBar> daily = IntStream.range(0, 20)
                .mapToObj(index -> bar(LocalDate.of(2026, 6, 1).plusDays(index), 100 + index, 1_000))
                .toList();

        assertThat(factory.toSeries(daily, "test").getBarCount()).isEqualTo(20);
    }

    private static DailyBar bar(LocalDate date, double close, double volume) {
        return new DailyBar(date, value(close), value(close + 1), value(close - 1), value(close),
                value(volume), value(close * volume));
    }

    private static BigDecimal value(double value) {
        return BigDecimal.valueOf(value);
    }
}
