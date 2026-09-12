package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.*;
import com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient;
import com.github.benmanes.caffeine.cache.*;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;

/** HiThink first for supported capabilities; existing providers remain independent fallbacks. */
public final class HithinkResearchGateway implements ResearchGateway {
    private final ResearchGateway primary;
    private final HithinkFinanceClient hithink;
    private final Cache<SecurityId,DataSection<FinancialStatementHistory>> historyCache =
            Caffeine.newBuilder().maximumSize(200).expireAfterWrite(Duration.ofMinutes(30)).build();
    private final Cache<SecurityId,DataSection<ValuationSnapshot>> valuationCache =
            Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<SecurityId,DataSection<List<CapitalData.DividendRecord>>> dividendCache =
            Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofHours(6)).build();
    private final Cache<SecurityId,DataSection<List<CapitalData.DragonTigerRecord>>> dragonTigerCache =
            Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofHours(1)).build();

    public HithinkResearchGateway(ResearchGateway primary, HithinkFinanceClient hithink) {
        this.primary = primary;
        this.hithink = hithink;
    }

    @Override public DataSection<ValuationSnapshot> valuation(SecurityId security) {
        return cached(valuationCache, security, () -> hithink.fetchValuation(security));
    }

    @Override public DataSection<FinancialStatementHistory> financialHistory(SecurityId security) {
        var preferred = cached(historyCache, security, () -> hithink.fetchStatementHistory(security));
        return prefer(preferred, () -> primary.financialHistory(security));
    }

    @Override public DataSection<?> fundamentals(SecurityId security) {
        var history = cached(historyCache, security, () -> hithink.fetchStatementHistory(security));
        if (history.payload().isEmpty() || history.payload().orElseThrow().periodCount() == 0)
            return fallback(history, attempt(() -> primary.fundamentals(security)));
        var latest = history.payload().orElseThrow().periods().getLast();
        Map<String,BigDecimal> values = new LinkedHashMap<>();
        put(values,"营业收入",latest.operatingRevenue());
        put(values,"营业成本",latest.operatingCost());
        put(values,"净利润",latest.netProfit());
        put(values,"归母净利润",latest.netProfitAttributable());
        put(values,"经营活动产生的现金流量净额",latest.operatingCashFlow());
        put(values,"资产总计",latest.totalAssets());
        put(values,"负债合计",latest.totalLiabilities());
        var converted = new DataSection<>(history.status(),
                Optional.of(new FundamentalData(latest.reportPeriod(),values,Map.of())),history.provenance(),history.issues());
        if (history.status() == SectionStatus.STALE || values.isEmpty())
            return fallback(history, attempt(() -> primary.fundamentals(security)));
        return converted;
    }

    private static void put(Map<String,BigDecimal> values,String key,BigDecimal value) {
        if (value != null) values.put(key,value);
    }

    public static <T> DataSection<T> prefer(DataSection<T> main, Supplier<DataSection<T>> backup) {
        if (hasData(main) && main.status() != SectionStatus.STALE) return main;
        var alternate = attempt(backup);
        if (!hasData(alternate) && hasData(main)) return main;
        return fallback(main, alternate);
    }

    private static boolean hasData(DataSection<?> section) {
        return section != null && section.status() != SectionStatus.UNAVAILABLE && section.payload().isPresent()
                && (!(section.payload().orElseThrow() instanceof List<?> list) || !list.isEmpty())
                && (!(section.payload().orElseThrow() instanceof FinancialStatementHistory h) || h.periodCount() > 0);
    }

    private static <T> DataSection<T> fallback(DataSection<?> main,DataSection<T> backup) {
        var issues = new ArrayList<>(main.issues());
        issues.add("同花顺数据不可用或陈旧，使用备用来源");
        issues.addAll(backup.issues());
        if (backup.payload().isEmpty() || backup.provenance().isEmpty())
            return DataSection.unavailable(String.join("；",issues));
        var p = backup.provenance().orElseThrow();
        var source = new Provenance(p.provider(),p.sourceUrl(),p.providerTimestamp(),p.fetchedAt(),p.cached(),"HiThink Finance");
        return new DataSection<>(backup.status() == SectionStatus.HEALTHY ? SectionStatus.DEGRADED : backup.status(),
                backup.payload(),Optional.of(source),issues);
    }

    private static <T> DataSection<T> attempt(Supplier<DataSection<T>> load) {
        try { return Objects.requireNonNull(load.get()); }
        catch (RuntimeException failure) { return DataSection.unavailable("备用来源请求失败"); }
    }

    private static <T> DataSection<T> cached(Cache<SecurityId,DataSection<T>> cache,SecurityId security,
            Supplier<DataSection<T>> loader) {
        var existing = cache.getIfPresent(security);
        if (existing == null) {
            // Caffeine coalesces concurrent loads for the same security.
            var loaded = cache.get(security, ignored -> safeLoad(loader));
            if (loaded.payload().isEmpty()) cache.invalidate(security);
            return loaded;
        }
        if (existing.payload().isEmpty()) { cache.invalidate(security); return safeLoad(loader); }
        var p = existing.provenance().orElseThrow();
        return new DataSection<>(existing.status(),existing.payload(),Optional.of(new Provenance(p.provider(),
                p.sourceUrl(),p.providerTimestamp(),p.fetchedAt(),true,p.fallbackProvider())),existing.issues());
    }

    private static <T> DataSection<T> safeLoad(Supplier<DataSection<T>> loader) {
        var loaded = loader.get();
        return loaded == null ? DataSection.unavailable("同花顺未返回结果") : loaded;
    }

    @Override public DataSection<Quote> quote(SecurityId s) {
        return prefer(hithink.fetchQuote(s), () -> primary.quote(s));
    }
    @Override public DataSection<List<DailyBar>> bars(SecurityId s) {
        return prefer(hithink.fetchDailyBars(s), () -> primary.bars(s));
    }
    @Override public DataSection<List<DailyBar>> crossCheckBars(SecurityId s) { return primary.crossCheckBars(s); }
    @Override public DataSection<?> sectors(SecurityId s) { return primary.sectors(s); }
    @Override public DataSection<IndustryValuationData> industryValuation(SecurityId s) { return primary.industryValuation(s); }
    @Override public DataSection<?> fundFlow(SecurityId s) { return primary.fundFlow(s); }
    @Override public DataSection<?> capital(SecurityId s) {
        var dividends = cached(dividendCache,s,() -> hithink.fetchDividends(s));
        var dragonTiger = cached(dragonTigerCache,s,() -> hithink.fetchDragonTiger(s));
        var legacy = attempt(() -> primary.capital(s));
        var base = legacy.payload().filter(CapitalData.class::isInstance).map(CapitalData.class::cast)
                .orElseGet(() -> new CapitalData(null,null,null,null,null,null));
        if (!hasData(dividends) && !hasData(dragonTiger)) return fallback(dividends,legacy);
        DataSection<?> legacyMetadata = legacy.payload().isPresent()
                ? new DataSection<>(legacy.status(),Optional.of("融资融券、大宗交易、股东、解禁及历史龙虎榜"),
                        legacy.provenance(),legacy.issues()) : legacy;
        var selectedDividends = dividends.payload().orElse(base.dividends());
        var selectedDragonTiger = dragonTiger.payload().orElse(base.dragonTigerRecords());
        var payload = new CapitalData(base.marginHistory(),base.blockTrades(),base.shareholderChanges(),
                base.unlocks(),selectedDividends,selectedDragonTiger,
                Map.of("dividends",dividends,"dragonTiger",dragonTiger,"legacyCapital",legacyMetadata));
        var issues = new ArrayList<>(dividends.issues());
        issues.addAll(dragonTiger.issues());
        issues.add("分红、龙虎榜优先同花顺；其余筹码明细沿用原来源，各项来源与状态见 components");
        issues.addAll(legacy.issues());
        var p = (hasData(dividends) ? dividends : dragonTiger).provenance().orElseThrow();
        String provider = p.provider() + legacy.provenance().map(v -> " + " + v.provider()).orElse("");
        var source = new Provenance(provider,p.sourceUrl(),p.providerTimestamp(),p.fetchedAt(),p.cached(),null);
        return DataSection.degraded(payload,source,issues);
    }
    @Override public DataSection<?> research(SecurityId s) { return primary.research(s); }
    @Override public DataSection<?> news(SecurityId s) { return primary.news(s); }
    @Override public DataSection<?> announcements(SecurityId s) { return primary.announcements(s); }
}
