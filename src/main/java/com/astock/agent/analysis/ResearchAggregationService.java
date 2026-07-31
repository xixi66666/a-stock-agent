package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SectionStatus;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.technical.TechnicalAnalysisService;
import com.astock.agent.technical.TechnicalSnapshot;
import com.astock.agent.technical.Timeframe;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ResearchAggregationService {

    private final ResearchGateway gateway;
    private final TechnicalAnalysisService technicalService;
    private final DataQualityScorer qualityScorer;
    private final Cache<SecurityId, StockResearchSnapshot> cache;

    public ResearchAggregationService(
            ResearchGateway gateway,
            TechnicalAnalysisService technicalService,
            DataQualityScorer qualityScorer,
            Cache<SecurityId, StockResearchSnapshot> cache) {
        this.gateway = gateway;
        this.technicalService = technicalService;
        this.qualityScorer = qualityScorer;
        this.cache = cache;
    }

    public StockResearchSnapshot research(SecurityId security) {
        return cache.get(security, this::load);
    }

    public void invalidate(SecurityId security) {
        cache.invalidate(security);
    }

    @SuppressWarnings("unchecked")
    private StockResearchSnapshot load(SecurityId security) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<DataSection<Quote>> quoteFuture = executor.submit(() -> safeQuote(security));
            Future<DataSection<List<DailyBar>>> barsFuture = executor.submit(() -> safeBars(security));
            Future<DataSection<List<DailyBar>>> crossFuture = executor.submit(() -> safeCrossBars(security));
            Future<DataSection<?>> sectorsFuture = submit(executor, () -> gateway.sectors(security));
            Future<DataSection<IndustryValuationData>> industryValuationFuture =
                    executor.submit(() -> safeIndustryValuation(security));
            Future<DataSection<?>> flowFuture = submit(executor, () -> gateway.fundFlow(security));
            Future<DataSection<?>> capitalFuture = submit(executor, () -> gateway.capital(security));
            Future<DataSection<?>> fundamentalsFuture = submit(executor, () -> gateway.fundamentals(security));
            Future<DataSection<?>> researchFuture = submit(executor, () -> gateway.research(security));
            Future<DataSection<?>> newsFuture = submit(executor, () -> gateway.news(security));
            Future<DataSection<?>> announcementsFuture = submit(executor, () -> gateway.announcements(security));

            DataSection<Quote> quote = quoteFuture.get();
            DataSection<List<DailyBar>> bars = barsFuture.get();
            if (!usable(quote) && !usable(bars)) {
                throw new ResearchUnavailableException("No core quote or K-line source is available for " + security.code());
            }
            DataSection<List<DailyBar>> cross = crossFuture.get();
            DataSection<TechnicalSnapshot> technical = technical(bars);
            boolean consistent = consistent(bars, cross);
            boolean complete = quote.payload().isPresent()
                    && bars.payload().map(values -> values.size() >= 260).orElse(false)
                    && usable(technical);
            boolean authoritative = quote.provenance()
                    .map(Provenance::provider)
                    .map(name -> name.contains("Tencent"))
                    .orElse(false);

            StockResearchSnapshot snapshot = new StockResearchSnapshot(
                    security, quote, bars, technical,
                    sectorsFuture.get(), industryValuationFuture.get(), flowFuture.get(), capitalFuture.get(), fundamentalsFuture.get(),
                    researchFuture.get(), newsFuture.get(), announcementsFuture.get(), null,
                    consistent, complete, authoritative, Instant.now());
            DataQualityBreakdown quality = qualityScorer.score(snapshot);
            return new StockResearchSnapshot(
                    security, quote, bars, technical,
                    snapshot.sectors(), snapshot.industryValuation(), snapshot.fundFlow(), snapshot.capital(), snapshot.fundamentals(),
                    snapshot.research(), snapshot.news(), snapshot.announcements(), quality,
                    consistent, complete, authoritative, snapshot.fetchedAt());
        } catch (ResearchUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResearchUnavailableException("Unable to aggregate research for " + security.code()
                    + ": " + exception.getMessage());
        }
    }

    private Future<DataSection<?>> submit(
            java.util.concurrent.ExecutorService executor, Callable<DataSection<?>> operation) {
        return executor.submit(() -> safe(operation));
    }

    private DataSection<Quote> safeQuote(SecurityId security) {
        try {
            return gateway.quote(security);
        } catch (Exception exception) {
            return DataSection.unavailable(message(exception));
        }
    }

    private DataSection<List<DailyBar>> safeBars(SecurityId security) {
        try {
            return gateway.bars(security);
        } catch (Exception exception) {
            return DataSection.unavailable(message(exception));
        }
    }

    private DataSection<List<DailyBar>> safeCrossBars(SecurityId security) {
        try {
            return gateway.crossCheckBars(security);
        } catch (Exception exception) {
            return DataSection.unavailable(message(exception));
        }
    }

    private DataSection<IndustryValuationData> safeIndustryValuation(SecurityId security) {
        try {
            return gateway.industryValuation(security);
        } catch (Exception exception) {
            return DataSection.unavailable(message(exception));
        }
    }

    private static DataSection<?> safe(Callable<DataSection<?>> operation) {
        try {
            return operation.call();
        } catch (Exception exception) {
            return DataSection.unavailable(message(exception));
        }
    }

    private DataSection<TechnicalSnapshot> technical(DataSection<List<DailyBar>> bars) {
        if (bars.payload().isEmpty()) {
            return DataSection.unavailable("Technical analysis requires K-line data");
        }
        try {
            Provenance source = bars.provenance().orElseThrow();
            return DataSection.healthy(technicalService.analyze(bars.payload().orElseThrow(), Timeframe.DAILY), source);
        } catch (Exception exception) {
            return DataSection.unavailable("Technical analysis failed: " + exception.getMessage());
        }
    }

    private static boolean consistent(
            DataSection<List<DailyBar>> primary, DataSection<List<DailyBar>> crossCheck) {
        if (primary.payload().isEmpty() || crossCheck.payload().isEmpty()
                || primary.payload().orElseThrow().isEmpty() || crossCheck.payload().orElseThrow().isEmpty()) {
            return false;
        }
        DailyBar left = primary.payload().orElseThrow().getLast();
        DailyBar right = crossCheck.payload().orElseThrow().getLast();
        if (!left.date().equals(right.date())) {
            return false;
        }
        double relative = Math.abs(left.close().doubleValue() - right.close().doubleValue())
                / Math.max(0.01, left.close().doubleValue());
        return relative <= 0.005;
    }

    private static boolean usable(DataSection<?> section) {
        return section.status() != SectionStatus.UNAVAILABLE && section.payload().isPresent();
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
