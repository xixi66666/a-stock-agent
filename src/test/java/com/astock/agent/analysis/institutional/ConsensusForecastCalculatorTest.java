package com.astock.agent.analysis.institutional;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.ResearchItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsensusForecastCalculatorTest {

    @Test
    void usesLatestReportPerInstitutionAndMedianEps() {
        List<ResearchItem> reports = List.of(
                report("Alpha", "2026-07-01", "5.0", "6.0", "增持"),
                report("Alpha", "2026-07-20", "5.4", "6.3", "买入"),
                report("Beta", "2026-07-18", "5.8", "6.7", "增持"),
                report("Gamma", "2026-07-19", null, "7.1", "中性"));

        ConsensusForecast result = new ConsensusForecastCalculator().calculate(reports);

        assertThat(result.coverage()).isEqualTo(3);
        assertThat(result.currentYearEpsMedian()).isEqualByComparingTo("5.6");
        assertThat(result.nextYearEpsMedian()).isEqualByComparingTo("6.7");
        assertThat(result.ratingDistribution())
                .containsEntry("买入", 1)
                .containsEntry("增持", 1)
                .containsEntry("中性", 1);
    }

    @Test
    void returnsEmptyForecastWhenReportsAreMissing() {
        ConsensusForecast result = new ConsensusForecastCalculator().calculate(List.of());

        assertThat(result.coverage()).isZero();
        assertThat(result.currentYearEpsMedian()).isNull();
        assertThat(result.nextYearEpsMedian()).isNull();
        assertThat(result.ratingDistribution()).isEmpty();
    }

    private static ResearchItem report(
            String institution, String date, String current, String next, String rating) {
        return new ResearchItem(
                "report", institution, rating, LocalDate.parse(date), "https://example.test/report",
                current == null ? null : new BigDecimal(current),
                next == null ? null : new BigDecimal(next));
    }
}
