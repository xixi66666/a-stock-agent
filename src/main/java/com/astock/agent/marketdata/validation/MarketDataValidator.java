package com.astock.agent.marketdata.validation;

import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MarketDataValidator {

    private static final int PROFESSIONAL_HISTORY_SIZE = 260;

    public List<ValidationIssue> validateQuote(Quote quote, SecurityId requested, Clock clock) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (!requested.equals(quote.security())) {
            issues.add(new ValidationIssue("IDENTITY_MISMATCH", "Quote security does not match request"));
        }
        requireNonNegative(issues, "PRICE_NEGATIVE", "price", quote.price());
        requireNonNegative(issues, "VOLUME_NEGATIVE", "volume", quote.volumeShares());
        requireNonNegative(issues, "AMOUNT_NEGATIVE", "amount", quote.amountYuan());
        Instant quotedAt = quote.quotedAt();
        if (quotedAt != null && quotedAt.isAfter(clock.instant().plus(Duration.ofMinutes(5)))) {
            issues.add(new ValidationIssue("TIMESTAMP_FUTURE", "Quote timestamp is materially in the future"));
        }
        return List.copyOf(issues);
    }

    public List<ValidationIssue> validateBars(List<DailyBar> bars) {
        List<ValidationIssue> issues = new ArrayList<>();
        Set<java.time.LocalDate> dates = new HashSet<>();
        java.time.LocalDate previous = null;
        for (DailyBar bar : bars) {
            if (!dates.add(bar.date())) {
                issues.add(new ValidationIssue("DUPLICATE_DATE", "Duplicate bar date: " + bar.date()));
            }
            if (previous != null && bar.date().isBefore(previous)) {
                issues.add(new ValidationIssue("DATE_ORDER", "Bar dates are not ascending"));
            }
            previous = bar.date();
            if (!validOhlc(bar)) {
                issues.add(new ValidationIssue("OHLC_RANGE", "OHLC relationship is invalid on " + bar.date()));
            }
            requireNonNegative(issues, "VOLUME_NEGATIVE", "volume", bar.volumeShares());
            requireNonNegative(issues, "AMOUNT_NEGATIVE", "amount", bar.amountYuan());
        }
        if (bars.size() < PROFESSIONAL_HISTORY_SIZE) {
            issues.add(new ValidationIssue(
                    "INSUFFICIENT_HISTORY",
                    "At least " + PROFESSIONAL_HISTORY_SIZE + " daily bars are required; received " + bars.size()));
        }
        return List.copyOf(issues);
    }

    private static boolean validOhlc(DailyBar bar) {
        if (bar.open() == null || bar.high() == null || bar.low() == null || bar.close() == null) {
            return false;
        }
        BigDecimal max = bar.open().max(bar.close()).max(bar.low());
        BigDecimal min = bar.open().min(bar.close()).min(bar.high());
        return bar.high().compareTo(max) >= 0 && bar.low().compareTo(min) <= 0;
    }

    private static void requireNonNegative(
            List<ValidationIssue> issues, String code, String field, BigDecimal value) {
        if (value != null && value.signum() < 0) {
            issues.add(new ValidationIssue(code, field + " cannot be negative"));
        }
    }
}
