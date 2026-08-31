package com.astock.agent.analysis.financial;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.Exchange;
import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class FinancialQualityScorerTest {

    private final FinancialQualityScorer scorer = new FinancialQualityScorer();

    private static final String[] PERIODS = {
        "2023-06-30", "2023-09-30", "2023-12-31", "2024-03-31",
        "2024-06-30", "2024-09-30", "2024-12-31", "2025-03-31",
        "2025-06-30", "2025-09-30", "2025-12-31", "2026-03-31",
    };

    @Test
    void healthyGrowthHistoryScoresHigh() {
        FinancialStatementHistory history = history(placeholder -> {
            int index = Arrays.asList(PERIODS).indexOf(placeholder.reportPeriod().toString());
            return statement(placeholder.reportPeriod(), 1000 + index * 50, 350 + index * 10,
                    120 + index * 8, 110 + index * 8, 300 + index * 20, 5000 + index * 200,
                    2000 + index * 60, 1500 + index * 40, 800 + index * 30, 500, 2500 + index * 100);
        });
        FinancialQualityScore score = scorer.score(history, false);

        assertThat(score.sufficientData()).isTrue();
        assertThat(score.total()).isGreaterThanOrEqualTo(7);
        assertThat(score.tier()).isIn("良", "优");
        assertThat(score.signals()).hasSize(9);
        assertThat(score.signals()).noneMatch(signal -> signal.status() == FinancialQualityScore.SignalStatus.UNVERIFIED);
    }

    @Test
    void deterioratingHistoryScoresLow() {
        FinancialStatementHistory history = history(placeholder -> {
            int index = Arrays.asList(PERIODS).indexOf(placeholder.reportPeriod().toString());
            return statement(placeholder.reportPeriod(), 1000 - index * 20, 350 + index * 5,
                    Math.max(10, 120 - index * 10), 90 - index * 8, Math.max(20, 300 - index * 30),
                    5000 + index * 300, 2000 + index * 150, 1500 - index * 50, 800 + index * 60,
                    500 + index * 10, 2500 - index * 50);
        });
        FinancialQualityScore score = scorer.score(history, false);

        assertThat(score.sufficientData()).isTrue();
        assertThat(score.total()).isLessThanOrEqualTo(2);
        assertThat(score.tier()).isEqualTo("弱");
    }

    @Test
    void missingFieldsBecomeUnverifiedAndExcludedFromTotal() {
        FinancialStatementHistory history = history(placeholder -> statement(placeholder.reportPeriod(),
                null, null, null, null, null, 5000, 2000, null, null, null, null));
        FinancialQualityScore score = scorer.score(history, false);

        assertThat(score.evaluatedSignals()).isLessThan(9);
        assertThat(score.signals().stream()
                .filter(signal -> signal.status() == FinancialQualityScore.SignalStatus.UNVERIFIED)).isNotEmpty();
    }

    @Test
    void fewerThanFourPeriodsIsInsufficient() {
        FinancialStatementHistory history = history(placeholder -> statement(placeholder.reportPeriod(),
                1000, 350, 120, 110, 300, 5000, 2000, 1500, 800, 500, 2500));
        FinancialQualityScore score = scorer.score(subset(history, 3), false);

        assertThat(score.sufficientData()).isFalse();
        assertThat(score.tier()).isEqualTo("数据不足");
    }

    @Test
    void financialIndustryMarksMarginAndTurnoverUnverified() {
        FinancialStatementHistory history = history(placeholder -> {
            int index = Arrays.asList(PERIODS).indexOf(placeholder.reportPeriod().toString());
            return statement(placeholder.reportPeriod(), 1000 + index * 50, 350 + index * 10,
                    120 + index * 8, 110 + index * 8, 300 + index * 20, 5000 + index * 200,
                    2000 + index * 60, 1500 + index * 40, 800 + index * 30, 500, 2500 + index * 100);
        });
        FinancialQualityScore score = scorer.score(history, true);

        assertThat(score.signals().get(7).status()).isEqualTo(FinancialQualityScore.SignalStatus.UNVERIFIED);
        assertThat(score.signals().get(8).status()).isEqualTo(FinancialQualityScore.SignalStatus.UNVERIFIED);
    }

    @Test
    void ttmUsesCumulativeComposition() {
        FinancialPeriodStatement sameLastYear = statement(LocalDate.parse("2025-03-31"),
                270, 95, 32, 29, 80, 5800, 2100, 1700, 880, 500, 2900);
        FinancialPeriodStatement annual = statement(LocalDate.parse("2025-12-31"),
                1300, 455, 156, 143, 390, 6000, 2200, 1750, 900, 500, 3000);
        FinancialPeriodStatement q3 = statement(LocalDate.parse("2026-03-31"),
                300, 105, 36, 33, 90, 6200, 2300, 1800, 950, 500, 3100);
        List<FinancialPeriodStatement> periods = new ArrayList<>();
        periods.add(sameLastYear);
        periods.add(annual);
        periods.add(q3);

        BigDecimal ttmRevenue = FinancialTtm.ttm(periods, 2, FinancialPeriodStatement::operatingRevenue);

        assertThat(ttmRevenue).isEqualByComparingTo("1330");
    }

    private static FinancialPeriodStatement statement(LocalDate period, Number revenue, Number cost,
            Number netProfit, Number attributable, Number cashFlow, Number assets,
            Number liabilities, Number currentAssets, Number currentLiabilities,
            Number shareCapital, Number equity) {
        return new FinancialPeriodStatement(period, dec(revenue), dec(cost), dec(netProfit),
                dec(attributable), dec(cashFlow), dec(assets), dec(liabilities), dec(currentAssets),
                dec(currentLiabilities), dec(shareCapital), dec(equity));
    }

    private static BigDecimal dec(Number value) {
        return value == null ? null : BigDecimal.valueOf(value.doubleValue());
    }

    private static FinancialStatementHistory history(UnaryOperator<FinancialPeriodStatement> builder) {
        List<FinancialPeriodStatement> periods = new ArrayList<>();
        for (String period : PERIODS) {
            FinancialPeriodStatement placeholder = statement(LocalDate.parse(period),
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            periods.add(builder.apply(placeholder));
        }
        return new FinancialStatementHistory(new SecurityId("600519", Exchange.SHANGHAI), periods);
    }

    private static FinancialStatementHistory subset(FinancialStatementHistory history, int size) {
        return new FinancialStatementHistory(history.security(),
                new ArrayList<>(history.periods().subList(0, size)));
    }
}
