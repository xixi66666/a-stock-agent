package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.BenchmarkId;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.provider.BenchmarkDataGateway;
import com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient;
import java.util.List;

public final class HithinkBenchmarkGateway implements BenchmarkDataGateway {
    private final HithinkFinanceClient hithink;
    private final BenchmarkDataGateway backup;

    public HithinkBenchmarkGateway(HithinkFinanceClient hithink, BenchmarkDataGateway backup) {
        this.hithink = hithink;
        this.backup = backup;
    }

    @Override public DataSection<List<DailyBar>> bars(BenchmarkId benchmark) {
        return HithinkResearchGateway.prefer(hithink.fetchBenchmarkBars(benchmark), () -> backup.bars(benchmark));
    }
}
