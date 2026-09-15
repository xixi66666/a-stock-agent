package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.FundFlow;
import com.astock.agent.marketdata.model.FundFlowSummary;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SectionStatus;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.technical.TechnicalAnalysisService;
import com.astock.agent.technical.TechnicalSnapshot;
import com.astock.agent.technical.Timeframe;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 研究数据聚合服务。
 *
 * <p>把多个 Provider 的局部结果组合成一个带时间戳的不可变快照，并承担缓存、技术指标
 * 派生、交叉来源核验和资金流汇总。核心原则是部分成功：可选分区失败时保留其他健康分区，
 * 只有核心行情和主 K 线都不可用才让整个研究请求失败。</p>
 */
public final class ResearchAggregationService {

    private static final Duration DEFAULT_SECTION_TIMEOUT = Duration.ofSeconds(30);

    private final ResearchGateway gateway;
    private final TechnicalAnalysisService technicalService;
    private final DataQualityScorer qualityScorer;
    private final FundFlowSummaryCalculator fundFlowSummaryCalculator;
    private final Cache<SecurityId, StockResearchSnapshot> cache;
    private final Duration sectionTimeout;

    public ResearchAggregationService(
            ResearchGateway gateway,
            TechnicalAnalysisService technicalService,
            DataQualityScorer qualityScorer,
            FundFlowSummaryCalculator fundFlowSummaryCalculator,
            Cache<SecurityId, StockResearchSnapshot> cache) {
        this(gateway, technicalService, qualityScorer, fundFlowSummaryCalculator, cache,
                DEFAULT_SECTION_TIMEOUT);
    }

    public ResearchAggregationService(
            ResearchGateway gateway,
            TechnicalAnalysisService technicalService,
            DataQualityScorer qualityScorer,
            FundFlowSummaryCalculator fundFlowSummaryCalculator,
            Cache<SecurityId, StockResearchSnapshot> cache,
            Duration sectionTimeout) {
        this.gateway = gateway;
        this.technicalService = technicalService;
        this.qualityScorer = qualityScorer;
        this.fundFlowSummaryCalculator = fundFlowSummaryCalculator;
        this.cache = cache;
        this.sectionTimeout = sectionTimeout;
    }

    public StockResearchSnapshot research(SecurityId security) {
        // Cache 的 value 是完整快照，不缓存单个 Provider 的半成品，避免不同分区来自不同时间。
        return cache.get(security, this::load);
    }

    public void invalidate(SecurityId security) {
        cache.invalidate(security);
    }

    private StockResearchSnapshot load(SecurityId security) {
        // 虚拟线程并行等待独立 Provider；每个 Future 都通过 safe 方法转换成局部 DataSection。
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<DataSection<Quote>> quoteFuture = executor.submit(() -> safeQuote(security));
            Future<DataSection<List<DailyBar>>> barsFuture = executor.submit(() -> safeBars(security));
            Future<DataSection<List<DailyBar>>> crossFuture = executor.submit(() -> safeCrossBars(security));
            Future<DataSection<?>> sectorsFuture = submit(executor, () -> gateway.sectors(security));
            Future<DataSection<com.astock.agent.marketdata.model.ValuationSnapshot>> valuationFuture =
                    executor.submit(() -> {
                        try { return gateway.valuation(security); }
                        catch (RuntimeException failure) {
                            return DataSection.<com.astock.agent.marketdata.model.ValuationSnapshot>unavailable("估值请求失败");
                        }
                    });
            Future<DataSection<IndustryValuationData>> industryValuationFuture =
                    executor.submit(() -> safeIndustryValuation(security));
            Future<DataSection<?>> flowFuture = submit(executor, () -> gateway.fundFlow(security));
            Future<DataSection<?>> capitalFuture = submit(executor, () -> gateway.capital(security));
            Future<DataSection<?>> fundamentalsFuture = submit(executor, () -> gateway.fundamentals(security));
            Future<DataSection<?>> researchFuture = submit(executor, () -> gateway.research(security));
            Future<DataSection<?>> newsFuture = submit(executor, () -> gateway.news(security));
            Future<DataSection<?>> announcementsFuture = submit(executor, () -> gateway.announcements(security));

            // 分区等待必须限时：单个 Provider 卡死只降级自己的分区，不能拖住整份快照和缓存单飞锁。
            DataSection<Quote> quote = section(quoteFuture, "Quote");
            DataSection<List<DailyBar>> primaryBars = section(barsFuture, "K-line");
            DataSection<List<DailyBar>> cross = section(crossFuture, "Independent K-line");
            DataSection<List<DailyBar>> bars = preferFresherBars(primaryBars, cross);
            if (!usable(quote) && !usable(bars)) {
                throw new ResearchUnavailableException("No core quote or K-line source is available for " + security.code());
            }
            DataSection<TechnicalSnapshot> technical = technical(bars);
            boolean consistent = consistent(primaryBars, cross);
            boolean complete = quote.payload().isPresent()
                    && bars.payload().map(values -> values.size() >= 260).orElse(false)
                    && usable(technical);
            boolean authoritative = quote.provenance()
                    .map(Provenance::provider)
                    .map(name -> name.contains("Tencent") || name.equals("HiThink Finance"))
                    .orElse(false);
            var fundFlow = anySection(flowFuture, "Fund flow");
            DataSection<FundFlowSummary> fundFlowSummary = summarizeFlow(fundFlow);
            var sectors = anySection(sectorsFuture, "Sectors");
            DataSection<IndustryValuationData> industryValuation =
                    section(industryValuationFuture, "Industry valuation");
            var capital = anySection(capitalFuture, "Capital");
            var fundamentals = anySection(fundamentalsFuture, "Fundamentals");
            var research = anySection(researchFuture, "Institutional research");
            var news = anySection(newsFuture, "News");
            var announcements = anySection(announcementsFuture, "Announcements");
            DataSection<com.astock.agent.marketdata.model.ValuationSnapshot> valuation =
                    section(valuationFuture, "Valuation");

            StockResearchSnapshot snapshot = new StockResearchSnapshot(
                    security, quote, bars, technical,
                    sectors, industryValuation, fundFlow, fundFlowSummary,
                    capital, fundamentals,
                    research, news, announcements, null,
                    consistent, complete, authoritative, Instant.now());
            DataQualityBreakdown quality = qualityScorer.score(snapshot);
            return new StockResearchSnapshot(
                    security, quote, bars, technical,
                    snapshot.sectors(), snapshot.industryValuation(), snapshot.fundFlow(), snapshot.fundFlowSummary(),
                    snapshot.capital(), snapshot.fundamentals(),
                    snapshot.research(), snapshot.news(), snapshot.announcements(), quality,
                    consistent, complete, authoritative, snapshot.fetchedAt(), valuation);
        } catch (ResearchUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResearchUnavailableException("Unable to aggregate research for " + security.code()
                    + ": " + exception.getMessage());
        }
    }

    private <T> DataSection<T> section(Future<DataSection<T>> future, String label) {
        return typed(sectionResult(future, label));
    }

    private DataSection<?> anySection(Future<? extends DataSection<?>> future, String label) {
        return sectionResult(future, label);
    }

    /** 限时等待分区结果：超时取消任务并按不可用分区返回，避免单个 Provider 拖住整份快照。 */
    private DataSection<?> sectionResult(Future<? extends DataSection<?>> future, String label) {
        try {
            return future.get(sectionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return DataSection.unavailable(label + " section timed out after " + sectionTimeout.toMillis() + "ms");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return DataSection.unavailable(label + " section request was interrupted");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            return DataSection.unavailable(cause == null || cause.getMessage() == null
                    ? label + " section failed" : cause.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> DataSection<T> typed(DataSection<?> result) {
        return (DataSection<T>) result;
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

    private DataSection<FundFlowSummary> summarizeFlow(DataSection<?> source) {
        if (source.payload().isEmpty()) {
            return DataSection.unavailable(source.issues().isEmpty()
                    ? "Fund-flow data is unavailable"
                    : source.issues().getFirst());
        }
        Object payload = source.payload().orElseThrow();
        if (!(payload instanceof List<?> values)) {
            return DataSection.unavailable("Fund-flow payload has an invalid type");
        }
        List<FundFlow> flows = values.stream()
                .filter(FundFlow.class::isInstance)
                .map(FundFlow.class::cast)
                .toList();
        FundFlowSummary summary = fundFlowSummaryCalculator.calculate(flows);
        Provenance provenance = source.provenance().orElse(null);
        if (provenance == null) {
            return DataSection.unavailable("Fund-flow provenance is unavailable");
        }
        if (flows.isEmpty()) {
            List<String> issues = new ArrayList<>(source.issues());
            issues.add("Fund-flow provider returned an empty history");
            return DataSection.degraded(summary, provenance, issues);
        }
        return switch (source.status()) {
            case HEALTHY -> DataSection.healthy(summary, provenance);
            case DEGRADED -> DataSection.degraded(summary, provenance, source.issues());
            case STALE -> DataSection.stale(summary, provenance, source.issues());
            case UNVERIFIED -> DataSection.unverified(summary, provenance, source.issues());
            case UNAVAILABLE -> DataSection.unavailable(source.issues().isEmpty()
                    ? "Fund-flow data is unavailable"
                    : source.issues().getFirst());
        };
    }

    private DataSection<TechnicalSnapshot> technical(DataSection<List<DailyBar>> bars) {
        if (bars.payload().isEmpty()) {
            return DataSection.unavailable("Technical analysis requires K-line data");
        }
        try {
            Provenance source = bars.provenance().orElseThrow();
            return new DataSection<>(bars.status(), java.util.Optional.of(
                    technicalService.analyze(bars.payload().orElseThrow(), Timeframe.DAILY)),
                    java.util.Optional.of(source), bars.issues());
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

    /**
     * 主 K 线源出现滞后时，允许更新的独立核验源接管分析输入；来源和降级原因必须保留。
     * 同一交易日仍以配置的优先来源为准，避免仅因价格微小差异改变主数据口径。
     */
    private static DataSection<List<DailyBar>> preferFresherBars(
            DataSection<List<DailyBar>> primary, DataSection<List<DailyBar>> crossCheck) {
        if (!hasBars(primary) && hasBars(crossCheck)) {
            return recoveredBars(primary, crossCheck,
                    "Primary K-line source is unavailable; using independent K-line source");
        }
        if (!hasBars(primary) || !hasBars(crossCheck)) {
            return primary;
        }
        LocalDate primaryLatest = latestDate(primary);
        LocalDate crossLatest = latestDate(crossCheck);
        if (crossLatest.isAfter(primaryLatest)) {
            return recoveredBars(primary, crossCheck,
                    "Primary K-line latest date is " + primaryLatest
                            + "; using fresher " + crossCheck.provenance().orElseThrow().provider()
                            + " K-line dated " + crossLatest);
        }
        return primary;
    }

    private static DataSection<List<DailyBar>> recoveredBars(
            DataSection<List<DailyBar>> primary,
            DataSection<List<DailyBar>> replacement,
            String issue) {
        Provenance source = replacement.provenance().orElseThrow();
        Provenance recovered = new Provenance(
                source.provider(), source.sourceUrl(), source.providerTimestamp(), source.fetchedAt(),
                source.cached(), primary.provenance().map(Provenance::provider).orElse(null));
        List<String> issues = new ArrayList<>(replacement.issues());
        issues.add(issue);
        return DataSection.degraded(replacement.payload().orElseThrow(), recovered, issues);
    }

    private static boolean hasBars(DataSection<List<DailyBar>> section) {
        return usable(section) && section.payload().map(values -> !values.isEmpty()).orElse(false);
    }

    private static LocalDate latestDate(DataSection<List<DailyBar>> section) {
        return section.payload().orElseThrow().stream()
                .map(DailyBar::date)
                .max(LocalDate::compareTo)
                .orElseThrow();
    }

    private static boolean usable(DataSection<?> section) {
        return section.status() != SectionStatus.UNAVAILABLE && section.payload().isPresent();
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
