package com.astock.agent.marketdata.provider.tencent;

import com.astock.agent.marketdata.model.BenchmarkId;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.provider.BenchmarkDataGateway;
import com.astock.agent.marketdata.provider.ProviderHttpClient;
import com.astock.agent.marketdata.provider.ProviderId;
import java.net.URI;
import java.time.Clock;
import java.util.List;

public final class TencentBenchmarkDataGateway implements BenchmarkDataGateway {

    private final ProviderHttpClient http;
    private final TencentResponseParser parser;
    private final Clock clock;

    public TencentBenchmarkDataGateway(ProviderHttpClient http, TencentResponseParser parser, Clock clock) {
        this.http = http;
        this.parser = parser;
        this.clock = clock;
    }

    @Override
    public DataSection<List<DailyBar>> bars(BenchmarkId benchmark) {
        if (benchmark == null) {
            return DataSection.unavailable("Benchmark is required");
        }
        URI uri = URI.create("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param="
                + benchmark.tencentCode() + ",day,,,520,qfq");
        try {
            List<DailyBar> bars = parser.parseDailyBars(
                    http.get(ProviderId.TENCENT, uri, "https://gu.qq.com/").utf8Text(),
                    benchmark.tencentCode());
            if (bars.isEmpty()) {
                return DataSection.unavailable(benchmark.displayName() + " returned empty daily bars");
            }
            return DataSection.healthy(bars, new Provenance(
                    ProviderId.TENCENT.displayName(), uri, null, clock.instant(), false, null));
        } catch (Exception exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            return DataSection.unavailable(benchmark.displayName() + " benchmark bars failed: " + message);
        }
    }
}
