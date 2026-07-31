package com.astock.agent.analysis;

import com.astock.agent.analysis.institutional.IndustryValuationService;
import com.astock.agent.marketdata.model.Announcement;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.FundFlow;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.NewsItem;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.ResearchItem;
import com.astock.agent.marketdata.model.SectionStatus;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.baidu.BaiduKlineClient;
import com.astock.agent.marketdata.provider.cninfo.CninfoAnnouncementClient;
import com.astock.agent.marketdata.provider.eastmoney.EastmoneyResearchClient;
import com.astock.agent.marketdata.provider.sina.SinaFinanceClient;
import com.astock.agent.marketdata.provider.tencent.TencentMarketDataClient;
import com.github.benmanes.caffeine.cache.Cache;
import java.util.List;
import java.util.function.Supplier;

public final class ProviderResearchGateway implements ResearchGateway {

    private final TencentMarketDataClient tencent;
    private final BaiduKlineClient baidu;
    private final EastmoneyResearchClient eastmoney;
    private final SinaFinanceClient sina;
    private final CninfoAnnouncementClient cninfo;
    private final IndustryValuationService industryValuation;
    private final Cache<SecurityId, DataSection<List<ResearchItem>>> researchCache;
    private final Cache<SecurityId, DataSection<List<NewsItem>>> newsCache;
    private final Cache<SecurityId, DataSection<List<Announcement>>> announcementCache;

    public ProviderResearchGateway(
            TencentMarketDataClient tencent,
            BaiduKlineClient baidu,
            EastmoneyResearchClient eastmoney,
            SinaFinanceClient sina,
            CninfoAnnouncementClient cninfo,
            IndustryValuationService industryValuation,
            Cache<SecurityId, DataSection<List<ResearchItem>>> researchCache,
            Cache<SecurityId, DataSection<List<NewsItem>>> newsCache,
            Cache<SecurityId, DataSection<List<Announcement>>> announcementCache) {
        this.tencent = tencent;
        this.baidu = baidu;
        this.eastmoney = eastmoney;
        this.sina = sina;
        this.cninfo = cninfo;
        this.industryValuation = industryValuation;
        this.researchCache = researchCache;
        this.newsCache = newsCache;
        this.announcementCache = announcementCache;
    }

    @Override
    public DataSection<Quote> quote(SecurityId security) {
        var result = tencent.fetchQuote(security);
        return DataSection.healthy(result.payload(), result.provenance());
    }

    @Override
    public DataSection<List<DailyBar>> bars(SecurityId security) {
        var result = tencent.fetchDailyBars(security, 520);
        return DataSection.healthy(result.payload(), result.provenance());
    }

    @Override
    public DataSection<List<DailyBar>> crossCheckBars(SecurityId security) {
        var result = baidu.fetchDailyBars(security);
        return DataSection.healthy(result.bars(), result.provenance());
    }

    @Override public DataSection<?> sectors(SecurityId security) { return eastmoney.fetchSectors(security); }
    @Override public DataSection<IndustryValuationData> industryValuation(SecurityId security) {
        return industryValuation.compare(security);
    }

    @Override
    public DataSection<?> fundFlow(SecurityId security) {
        DataSection<List<FundFlow>> primary = eastmoney.fetchFundFlow(security);
        if (primary.status() != SectionStatus.UNAVAILABLE) {
            return primary;
        }
        DataSection<List<FundFlow>> fallback = sina.fetchFundFlow(security);
        if (fallback.payload().isEmpty() || fallback.provenance().isEmpty()) {
            return primary;
        }
        Provenance source = fallback.provenance().orElseThrow();
        Provenance degradedSource = new Provenance(
                source.provider(), source.sourceUrl(), source.providerTimestamp(), source.fetchedAt(),
                source.cached(), "Eastmoney");
        return DataSection.degraded(
                fallback.payload().orElseThrow(), degradedSource,
                List.of("Eastmoney unavailable; using Sina daily fund-flow fallback"));
    }

    @Override public DataSection<?> capital(SecurityId security) { return eastmoney.fetchCapitalData(security); }
    @Override public DataSection<?> fundamentals(SecurityId security) { return sina.fetchStatements(security); }
    @Override public DataSection<List<ResearchItem>> research(SecurityId security) {
        return cached(security, researchCache, () -> eastmoney.fetchReports(security));
    }
    @Override public DataSection<List<NewsItem>> news(SecurityId security) {
        return cached(security, newsCache, () -> eastmoney.fetchNews(security));
    }
    @Override public DataSection<List<Announcement>> announcements(SecurityId security) {
        return cached(security, announcementCache, () -> cninfo.fetchAnnouncements(security));
    }

    private static <T> DataSection<T> cached(
            SecurityId security, Cache<SecurityId, DataSection<T>> cache, Supplier<DataSection<T>> loader) {
        DataSection<T> existing = cache.getIfPresent(security);
        if (existing != null) {
            return markCached(existing);
        }
        DataSection<T> loaded = loader.get();
        if (loaded.payload().isPresent() && loaded.provenance().isPresent()) {
            cache.put(security, loaded);
        }
        return loaded;
    }

    private static <T> DataSection<T> markCached(DataSection<T> section) {
        Provenance source = section.provenance().orElseThrow();
        Provenance cached = new Provenance(
                source.provider(), source.sourceUrl(), source.providerTimestamp(), source.fetchedAt(), true,
                source.fallbackProvider());
        return switch (section.status()) {
            case HEALTHY -> DataSection.healthy(section.payload().orElseThrow(), cached);
            case DEGRADED -> DataSection.degraded(section.payload().orElseThrow(), cached, section.issues());
            case STALE -> DataSection.stale(section.payload().orElseThrow(), cached, section.issues());
            case UNVERIFIED -> DataSection.unverified(section.payload().orElseThrow(), cached, section.issues());
            case UNAVAILABLE -> section;
        };
    }
}
