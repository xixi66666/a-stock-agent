package com.astock.agent.analysis.financial;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.financial.FinancialTrendResult.TrendSeries;
import com.astock.agent.marketdata.model.Exchange;
import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialTrendCalculatorTest {

    private static final String[] PERIODS = {
        "2023-06-30", "2023-09-30", "2023-12-31", "2024-03-31",
        "2024-06-30", "2024-09-30", "2024-12-31", "2025-03-31",
        "2025-06-30", "2025-09-30", "2025-12-31", "2026-03-31",
    };

    private final FinancialTrendCalculator calculator = new FinancialTrendCalculator();

    @Test
    void producesEightSeriesWithFullPeriods() {
        FinancialTrendResult result = calculator.calculate(history());

        assertThat(result.periodCount()).isEqualTo(12);
        assertThat(result.series()).hasSize(8);
        assertThat(result.series().get(0).name()).isEqualTo("营业总收入");
        assertThat(result.series().get(0).caliber()).isEqualTo("CUMULATIVE");
        assertThat(result.series().get(0).points()).hasSize(12);
        assertThat(result.series().get(7).name()).isEqualTo("资产负债率");
        assertThat(result.series().get(7).caliber()).isEqualTo("POINT_IN_TIME");
    }

    @Test
    void yoyGrowthComparesWithSamePeriodLastYear() {
        FinancialTrendResult result = calculator.calculate(history());

        TrendSeries revenue = result.series().get(0);
        assertThat(revenue.yoyGrowthPercent()).hasSize(12);
        assertThat(revenue.yoyGrowthPercent().subList(0, 4)).containsOnlyNulls();
        assertThat(revenue.yoyGrowthPercent().get(11)).isNotNull();
        assertThat(revenue.yoyGrowthPercent().get(11)).isEqualByComparingTo("14.81");
    }

    @Test
    void directionFollowsRecentComparablePairs() {
        FinancialTrendResult result = calculator.calculate(history());

        assertThat(result.series().get(0).direction()).isEqualTo("RISING");
    }

    @Test
    void accelerationIsNullWhenLessThanTwoPairs() {
        FinancialStatementHistory shortHistory = new FinancialStatementHistory(
                new SecurityId("600519", Exchange.SHANGHAI),
                history().periods().subList(0, 6));
        FinancialTrendResult result = calculator.calculate(shortHistory);

        assertThat(result.series().get(0).acceleration()).isNull();
    }

    private static FinancialStatementHistory history() {
        List<FinancialPeriodStatement> periods = new ArrayList<>();
        for (int i = 0; i < PERIODS.length; i++) {
            periods.add(new FinancialPeriodStatement(
                    LocalDate.parse(PERIODS[i]),
                    BigDecimal.valueOf(1000 + i * 50L),
                    BigDecimal.valueOf(350 + i * 10L),
                    BigDecimal.valueOf(120 + i * 8L),
                    BigDecimal.valueOf(110 + i * 8L),
                    BigDecimal.valueOf(300 + i * 20L),
                    BigDecimal.valueOf(5000 + i * 200L),
                    BigDecimal.valueOf(2000 + i * 60L),
                    BigDecimal.valueOf(1500 + i * 40L),
                    BigDecimal.valueOf(800 + i * 30L),
                    BigDecimal.valueOf(500L),
                    BigDecimal.valueOf(2500 + i * 100L)));
        }
        return new FinancialStatementHistory(new SecurityId("600519", Exchange.SHANGHAI), periods);
    }
}
