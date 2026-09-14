package com.astock.agent.marketdata.provider;

import com.astock.agent.marketdata.model.BenchmarkId;
import com.astock.agent.marketdata.model.IndexQuoteSection;
import java.util.List;

/** 指数快照边界：主源优先，单指数失败保持局部。 */
public interface IndexQuoteGateway {
    List<IndexQuoteSection> quotes(List<BenchmarkId> benchmarks);
}
