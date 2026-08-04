package com.astock.agent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.FundFlow;
import com.astock.agent.marketdata.model.FundFlowSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FundFlowSummaryCalculatorTest {

    @Test
    void sortsDatesAndBuildsLatestFiveAndTwentyDayWindows() {
        List<FundFlow> values = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(day -> flow(LocalDate.of(2026, 7, day), day))
                .sorted(java.util.Comparator.comparing(FundFlow::date).reversed())
                .toList();
        List<FundFlow> scrambled = java.util.stream.Stream.concat(
                values.stream().filter(value -> value.date().getDayOfMonth() % 2 == 0),
                values.stream().filter(value -> value.date().getDayOfMonth() % 2 != 0)).toList();

        FundFlowSummary result = new FundFlowSummaryCalculator().calculate(scrambled);

        assertThat(result.latestDate()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(result.latestDay().sampleDays()).isEqualTo(1);
        assertThat(result.latestDay().mainNetYuan()).isEqualByComparingTo("20");
        assertThat(result.fiveDay().sampleDays()).isEqualTo(5);
        assertThat(result.fiveDay().mainNetYuan()).isEqualByComparingTo("90");
        assertThat(result.fiveDay().superLargeNetYuan()).isEqualByComparingTo("450");
        assertThat(result.fiveDay().largeNetYuan()).isEqualByComparingTo("360");
        assertThat(result.fiveDay().mediumNetYuan()).isEqualByComparingTo("270");
        assertThat(result.fiveDay().smallNetYuan()).isEqualByComparingTo("180");
        assertThat(result.twentyDay().sampleDays()).isEqualTo(20);
        assertThat(result.twentyDay().mainNetYuan()).isEqualByComparingTo("210");
        assertThat(result.twentyDay().superLargeNetYuan()).isEqualByComparingTo("1050");
        assertThat(result.twentyDay().largeNetYuan()).isEqualByComparingTo("840");
        assertThat(result.twentyDay().mediumNetYuan()).isEqualByComparingTo("630");
        assertThat(result.twentyDay().smallNetYuan()).isEqualByComparingTo("420");
    }

    @Test
    void shortWindowReportsActualDaysAndMissingCategoryAsNull() {
        FundFlow first = flow(LocalDate.of(2026, 7, 2), 2);
        FundFlow missingMedium = new FundFlow(LocalDate.of(2026, 7, 1),
                bd(1), bd(2), null, bd(4), bd(5), "fixture");

        FundFlowSummary result = new FundFlowSummaryCalculator().calculate(List.of(missingMedium, first));

        assertThat(result.fiveDay().sampleDays()).isEqualTo(2);
        assertThat(result.fiveDay().mainNetYuan()).isEqualByComparingTo("3");
        assertThat(result.fiveDay().mediumNetYuan()).isNull();
    }

    @Test
    void duplicateDateKeepsTheFirstRecordDeterministically() {
        LocalDate date = LocalDate.of(2026, 7, 15);

        FundFlowSummary result = new FundFlowSummaryCalculator().calculate(List.of(
                flow(date, 7), flow(date, 99), flow(date.minusDays(1), 3)));

        assertThat(result.latestDay().mainNetYuan()).isEqualByComparingTo("7");
        assertThat(result.fiveDay().sampleDays()).isEqualTo(2);
        assertThat(result.fiveDay().mainNetYuan()).isEqualByComparingTo("10");
    }

    @Test
    void emptyInputProducesExplicitZeroSampleWindowsWithoutAmounts() {
        FundFlowSummary result = new FundFlowSummaryCalculator().calculate(List.of());

        assertThat(result.latestDate()).isNull();
        assertThat(result.latestDay().sampleDays()).isZero();
        assertThat(result.fiveDay().sampleDays()).isZero();
        assertThat(result.twentyDay().sampleDays()).isZero();
        assertThat(result.twentyDay().mainNetYuan()).isNull();
    }

    private static FundFlow flow(LocalDate date, int value) {
        return new FundFlow(date, bd(value), bd(value * 2L), bd(value * 3L),
                bd(value * 4L), bd(value * 5L), "fixture");
    }

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
