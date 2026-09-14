package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.BenchmarkId;
import com.astock.agent.marketdata.provider.IndexQuoteGateway;
import java.util.List;

public final class MarketIndexService {
    public static final List<BenchmarkId> MARKET_INDICES = List.of(
            BenchmarkId.SHANGHAI_COMPOSITE, BenchmarkId.SHENZHEN_COMPONENT, BenchmarkId.CHI_NEXT);

    private final IndexQuoteGateway gateway;

    public MarketIndexService(IndexQuoteGateway gateway) {
        this.gateway = gateway;
    }

    public MarketIndexSnapshot snapshot() {
        return new MarketIndexSnapshot(gateway.quotes(MARKET_INDICES));
    }
}
