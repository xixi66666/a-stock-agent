package com.astock.agent.analysis.financial;

import com.astock.agent.marketdata.model.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnnualFinancialAnalysisTest {
    @Test void annualYoYAndLeverageCompareThePreviousYearNotFourRowsBack() {
        var history = new FinancialStatementHistory(SecurityId.parse("600519"),
                List.of(period(2022, "100", "50"), period(2023, "200", "50"),
                        period(2024, "300", "50"), period(2025, "330", "40")));
        var trend = new FinancialTrendCalculator().calculate(history).series().getFirst();
        assertThat(trend.yoyGrowthPercent().getLast()).isEqualByComparingTo("10");
        var score = new FinancialQualityScorer().score(history, false);
        assertThat(score.signals().get(4).status()).isEqualTo(FinancialQualityScore.SignalStatus.PASS);
    }

    private static FinancialPeriodStatement period(int year, String revenue, String debt) {
        return new FinancialPeriodStatement(LocalDate.of(year,12,31), new BigDecimal(revenue),
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal(debt), null, null, null, null);
    }
}
