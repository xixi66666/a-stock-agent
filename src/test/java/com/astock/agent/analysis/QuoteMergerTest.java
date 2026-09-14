package com.astock.agent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteMergerTest {
    private final SecurityId security = SecurityId.parse("600519");

    @Test void fillsOnlyNullFieldsAndKeepsPrimaryValues() {
        Quote primary = quote(new BigDecimal("100"), null, null);
        Quote tencent = quote(new BigDecimal("999"), new BigDecimal("10"), new BigDecimal("1.2"));
        var outcome = QuoteMerger.merge(primary, List.of(new QuoteMerger.Source("Tencent", tencent)));
        assertThat(outcome.quote().price()).isEqualByComparingTo("100");
        assertThat(outcome.quote().peTtm()).isEqualByComparingTo("10");
        assertThat(outcome.quote().pb()).isEqualByComparingTo("1.2");
        assertThat(outcome.filledByProvider()).containsOnlyKeys("Tencent");
        assertThat(outcome.filledByProvider().get("Tencent")).containsExactly("市盈率TTM", "市净率");
    }

    @Test void laterSourcesFillWhatEarlierSourcesCannot() {
        Quote empty = quote(null, null, null);
        Quote tencent = quote(null, new BigDecimal("10"), null);
        Quote eastmoney = quote(null, new BigDecimal("11"), new BigDecimal("1.5"));
        var outcome = QuoteMerger.merge(empty, List.of(
                new QuoteMerger.Source("Tencent", tencent),
                new QuoteMerger.Source("Eastmoney", eastmoney)));
        assertThat(outcome.quote().peTtm()).isEqualByComparingTo("10");
        assertThat(outcome.quote().pb()).isEqualByComparingTo("1.5");
        assertThat(outcome.filledByProvider().keySet()).containsExactly("Tencent", "Eastmoney");
        assertThat(outcome.filledByProvider().get("Eastmoney")).containsExactly("市净率");
    }

    @Test void recordsNothingWhenNoFieldWasFilled() {
        Quote complete = quote(new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("1.2"));
        var outcome = QuoteMerger.merge(complete, List.of(
                new QuoteMerger.Source("Tencent", quote(new BigDecimal("9"), null, null))));
        assertThat(outcome.quote()).isEqualTo(complete);
        assertThat(outcome.filledByProvider()).isEmpty();
    }

    private Quote quote(BigDecimal price, BigDecimal peTtm, BigDecimal pb) {
        return new Quote(security, "贵州茅台", price, new BigDecimal("99"), new BigDecimal("98"),
                new BigDecimal("101"), new BigDecimal("97"), null, null, null, null,
                null, null, null, peTtm, null, pb, null, null, null, null, Instant.parse("2026-09-14T01:00:00Z"));
    }
}
