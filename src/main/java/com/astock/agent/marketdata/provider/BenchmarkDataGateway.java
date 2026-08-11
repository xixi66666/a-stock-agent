package com.astock.agent.marketdata.provider;

import com.astock.agent.marketdata.model.BenchmarkId;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.DataSection;
import java.util.List;

public interface BenchmarkDataGateway {
    DataSection<List<DailyBar>> bars(BenchmarkId benchmark);
}
