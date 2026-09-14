package com.astock.agent.marketdata.model;

import java.util.Objects;

/**
 * 指数分区：把身份放在 DataSection 之外，UNAVAILABLE 时前端仍能定位到对应指数。
 */
public record IndexQuoteSection(BenchmarkId benchmark, String displayName, DataSection<IndexQuote> quote) {
    public IndexQuoteSection {
        Objects.requireNonNull(benchmark, "benchmark");
        Objects.requireNonNull(quote, "quote");
        displayName = displayName == null ? benchmark.displayName() : displayName;
    }
}
