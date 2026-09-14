package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.*;
import com.astock.agent.marketdata.provider.BenchmarkDataGateway;
import com.astock.agent.marketdata.provider.IndexQuoteGateway;
import com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** HiThink index snapshot first; Tencent benchmark daily bars fill only unavailable indices. */
public final class HithinkIndexQuoteGateway implements IndexQuoteGateway {
    private final HithinkFinanceClient hithink;
    private final BenchmarkDataGateway bars;

    public HithinkIndexQuoteGateway(HithinkFinanceClient hithink, BenchmarkDataGateway bars) {
        this.hithink = hithink;
        this.bars = bars;
    }

    @Override public List<IndexQuoteSection> quotes(List<BenchmarkId> benchmarks) {
        if (benchmarks == null || benchmarks.isEmpty()) return List.of();
        List<BenchmarkId> requested = List.copyOf(benchmarks);
        List<DataSection<IndexQuote>> primary = hithink.fetchIndexQuotes(requested);
        var result = new ArrayList<IndexQuoteSection>();
        for (int i = 0; i < requested.size(); i++) {
            BenchmarkId benchmark = requested.get(i);
            DataSection<IndexQuote> section = i < primary.size()
                    ? primary.get(i) : DataSection.unavailable("同花顺指数行情未返回该指数");
            if (section.payload().isPresent() && section.status() != SectionStatus.UNAVAILABLE) {
                result.add(new IndexQuoteSection(benchmark, benchmark.displayName(), section));
            } else {
                result.add(new IndexQuoteSection(benchmark, benchmark.displayName(), fallback(benchmark, section)));
            }
        }
        return List.copyOf(result);
    }

    private DataSection<IndexQuote> fallback(BenchmarkId benchmark, DataSection<IndexQuote> primary) {
        var issues = new ArrayList<>(primary.issues());
        DataSection<List<DailyBar>> history = bars.bars(benchmark);
        if (history.payload().isEmpty() || history.payload().orElseThrow().size() < 2
                || history.provenance().isEmpty()) {
            issues.add("腾讯基准日线回退不可用");
            issues.addAll(history.issues());
            return DataSection.unavailable(String.join("；", issues));
        }
        var series = history.payload().orElseThrow();
        var last = series.get(series.size() - 1);
        var previous = series.get(series.size() - 2);
        var change = last.close().subtract(previous.close());
        var percent = change.multiply(BigDecimal.valueOf(100))
                .divide(previous.close(), 4, RoundingMode.HALF_UP);
        var quote = new IndexQuote(benchmark, benchmark.displayName(), last.close(), previous.close(),
                change, percent, null);
        issues.add("腾讯基准日线回退：最新已完成交易日（" + last.date() + "）收盘口径，不是实时行情");
        issues.addAll(history.issues());
        var p = history.provenance().orElseThrow();
        var source = new Provenance(p.provider(), p.sourceUrl(), p.providerTimestamp(), p.fetchedAt(),
                p.cached(), "HiThink Finance");
        return DataSection.unverified(quote, source, issues);
    }
}
