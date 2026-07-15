package com.astock.agent.marketdata.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.DailyBar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketDataValidatorTest {

    private final MarketDataValidator validator = new MarketDataValidator();

    @Test
    void rejectsImpossibleOhlc() {
        DailyBar invalid = bar("2026-07-14", "10", "12", "9", "13");

        assertThat(validator.validateBars(List.of(invalid)))
                .extracting(ValidationIssue::code)
                .contains("OHLC_RANGE");
    }

    @Test
    void identifiesConflictingDuplicateDatesAndInsufficientHistory() {
        List<DailyBar> bars = new ArrayList<>();
        bars.add(bar("2026-07-14", "10", "12", "9", "11"));
        bars.add(bar("2026-07-14", "10", "13", "9", "12"));

        assertThat(validator.validateBars(bars))
                .extracting(ValidationIssue::code)
                .contains("DUPLICATE_DATE", "INSUFFICIENT_HISTORY");
    }

    private static DailyBar bar(String date, String open, String high, String low, String close) {
        return new DailyBar(
                LocalDate.parse(date), decimal(open), decimal(high), decimal(low), decimal(close),
                decimal("10000"), decimal("1000000"));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
