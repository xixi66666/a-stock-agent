package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.FundFlow;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SectionStatus;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.baidu.BaiduKlineClient;
import com.astock.agent.marketdata.provider.cninfo.CninfoAnnouncementClient;
import com.astock.agent.marketdata.provider.eastmoney.EastmoneyResearchClient;
import com.astock.agent.marketdata.provider.sina.SinaFinanceClient;
import com.astock.agent.marketdata.provider.tencent.TencentMarketDataClient;
import java.util.List;

public final class ProviderResearchGateway implements ResearchGateway {

    private final TencentMarketDataClient tencent;
    private final BaiduKlineClient baidu;
    private final EastmoneyResearchClient eastmoney;
    private final SinaFinanceClient sina;
    private final CninfoAnnouncementClient cninfo;

    public ProviderResearchGateway(
            TencentMarketDataClient tencent,
            BaiduKlineClient baidu,
            EastmoneyResearchClient eastmoney,
            SinaFinanceClient sina,
            CninfoAnnouncementClient cninfo) {
        this.tencent = tencent;
        this.baidu = baidu;
        this.eastmoney = eastmoney;
        this.sina = sina;
        this.cninfo = cninfo;
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
    @Override public DataSection<?> research(SecurityId security) { return eastmoney.fetchReports(security); }
    @Override public DataSection<?> news(SecurityId security) { return eastmoney.fetchNews(security); }
    @Override public DataSection<?> announcements(SecurityId security) { return cninfo.fetchAnnouncements(security); }
}
