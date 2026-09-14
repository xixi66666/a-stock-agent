package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.IndexQuoteSection;
import java.util.List;

public record MarketIndexSnapshot(List<IndexQuoteSection> indices) {
    public MarketIndexSnapshot {
        indices = indices == null ? List.of() : List.copyOf(indices);
    }
}
