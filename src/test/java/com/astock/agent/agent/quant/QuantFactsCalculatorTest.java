package com.astock.agent.agent.quant;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class QuantFactsCalculatorTest {

    private final QuantFactsCalculator calculator = new QuantFactsCalculator();

    @Test
    void calculatesObservableMetricsWithoutCompositeScore() {
        QuantReportFacts facts = calculator.calculate(snapshotWithBars(40), Map.of());

        assertThat(facts.metric("return-20").value()).isEqualByComparingTo("29.0");
        assertThat(facts.metric("max-drawdown").availability()).isEqualTo(MetricAvailability.AVAILABLE);
        assertThat(facts.toString()).doesNotContain("score", "rank", "confidence", "aggregate");
    }

    @Test
    void marksLongWindowRiskMetricsUnavailableWhenSampleIsTooShort() {
        QuantReportFacts facts = calculator.calculate(snapshotWithBars(10), Map.of());

        assertThat(facts.metric("return-20").availability()).isEqualTo(MetricAvailability.INSUFFICIENT_SAMPLE);
        assertThat(facts.metric("var-95").availability()).isEqualTo(MetricAvailability.INSUFFICIENT_SAMPLE);
    }

    @Test
    void distinguishesUnavailableBenchmarkFromInsufficientDateOverlap() {
        QuantReportFacts facts = calculator.calculate(snapshotWithBars(40), Map.of("CSI_300", List.of()));

        assertThat(facts.benchmarkComparisons().get("CSI_300").availability())
                .isEqualTo(MetricAvailability.UNAVAILABLE);
    }

    @Test
    void calculatesBenchmarkExcessReturnFromCumulativePairedPerformance() {
        List<DailyBar> stock = IntStream.range(0, 21)
                .mapToObj(index -> bar(index, 100d * Math.pow(1.01d, index)))
                .toList();
        List<DailyBar> benchmark = IntStream.range(0, 21)
                .mapToObj(index -> bar(index, 100d))
                .toList();
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"))
                .withBars(com.astock.agent.marketdata.model.DataSection.healthy(stock,
                        new com.astock.agent.marketdata.model.Provenance("Tencent", java.net.URI.create("https://example.test/bars"), null,
                                java.time.Instant.parse("2026-08-11T00:00:00Z"), false, null)));

        QuantReportFacts facts = calculator.calculate(snapshot, Map.of("CSI_300", benchmark));

        assertThat(facts.benchmarkComparisons().get("CSI_300").excessReturnPercent())
                .isBetween(new BigDecimal("22.01"), new BigDecimal("22.03"));
    }

    private static StockResearchSnapshot snapshotWithBars(int count) {
        List<DailyBar> bars = IntStream.range(0, count)
                .mapToObj(index -> new DailyBar(
                        LocalDate.of(2026, 1, 1).plusDays(index),
                        bd(99 + index), bd(101 + index), bd(98 + index),
                        bd(index < 20 ? 100 : 110 + index - 20),
                        bd(1000), bd(100000)))
                .toList();
        return StockResearchSnapshot.empty(SecurityId.parse("600519"))
                .withBars(com.astock.agent.marketdata.model.DataSection.healthy(
                        bars,
                        new com.astock.agent.marketdata.model.Provenance(
                                "Tencent", java.net.URI.create("https://example.test/bars"), null,
                                java.time.Instant.parse("2026-08-11T00:00:00Z"), false, null)));
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }

    private static DailyBar bar(int index, double close) {
        return new DailyBar(LocalDate.of(2026, 1, 1).plusDays(index), bd(close), bd(close), bd(close), bd(close), bd(1000), bd(100000));
    }
}
