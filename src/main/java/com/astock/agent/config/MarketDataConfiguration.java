package com.astock.agent.config;

import com.astock.agent.analysis.DataQualityScorer;
import com.astock.agent.analysis.FundFlowSummaryCalculator;
import com.astock.agent.analysis.ProviderResearchGateway;
import com.astock.agent.analysis.ResearchAggregationService;
import com.astock.agent.analysis.ResearchGateway;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.analysis.institutional.IndustryValuationCalculator;
import com.astock.agent.analysis.institutional.IndustryValuationService;
import com.astock.agent.marketdata.model.Announcement;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.IndustryPeerQuote;
import com.astock.agent.marketdata.model.NewsItem;
import com.astock.agent.marketdata.model.ResearchItem;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.model.Sector;
import com.astock.agent.marketdata.provider.ProviderHealthRegistry;
import com.astock.agent.marketdata.provider.ProviderHttpClient;
import com.astock.agent.marketdata.provider.ProviderThrottle;
import com.astock.agent.marketdata.provider.baidu.BaiduKlineClient;
import com.astock.agent.marketdata.provider.cninfo.CninfoAnnouncementClient;
import com.astock.agent.marketdata.provider.eastmoney.EastmoneyResearchClient;
import com.astock.agent.marketdata.provider.sina.SinaFinanceClient;
import com.astock.agent.marketdata.provider.tencent.TencentMarketDataClient;
import com.astock.agent.marketdata.provider.tencent.TencentResponseParser;
import com.astock.agent.technical.BarSeriesFactory;
import com.astock.agent.technical.TechnicalAnalysisService;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/**
 * 市场数据基础设施 Bean 组装。
 *
 * <p>把共享 HTTP 客户端、限流器、健康注册表、Provider 客户端和研究 Gateway 连接起来；
 * 这里不做具体数据分析，便于测试时替换某个 Provider 或使用离线 Fixture。</p>
 */
public class MarketDataConfiguration {

    @Bean Clock applicationClock() { return Clock.systemUTC(); }

    @Bean
    ProviderThrottle providerThrottle(MarketDataProperties properties) {
        return new ProviderThrottle(properties.eastmoneyMinInterval(), properties.eastmoneyJitter());
    }

    @Bean
    ProviderHealthRegistry providerHealthRegistry(MarketDataProperties properties, Clock clock) {
        return new ProviderHealthRegistry(properties.providerCooldown(), clock);
    }

    @Bean
    ProviderHttpClient providerHttpClient(
            MarketDataProperties properties,
            ProviderThrottle throttle,
            ProviderHealthRegistry healthRegistry,
            Clock clock) {
        return new ProviderHttpClient(
                properties.connectTimeout(), properties.requestTimeout(), properties.maxRetries(),
                throttle, healthRegistry, clock);
    }

    @Bean TencentResponseParser tencentResponseParser() { return new TencentResponseParser(); }
    @Bean BarSeriesFactory barSeriesFactory() { return new BarSeriesFactory(); }
    @Bean TechnicalAnalysisService technicalAnalysisService(BarSeriesFactory factory) {
        return new TechnicalAnalysisService(factory);
    }
    @Bean DataQualityScorer dataQualityScorer() { return new DataQualityScorer(); }
    @Bean FundFlowSummaryCalculator fundFlowSummaryCalculator() { return new FundFlowSummaryCalculator(); }
    @Bean IndustryValuationCalculator industryValuationCalculator() { return new IndustryValuationCalculator(); }

    @Bean
    TencentMarketDataClient tencentMarketDataClient(
            ProviderHttpClient http, TencentResponseParser parser, Clock clock) {
        return new TencentMarketDataClient(http, parser, clock);
    }

    @Bean BaiduKlineClient baiduKlineClient(ProviderHttpClient http, Clock clock) {
        return new BaiduKlineClient(http, clock);
    }

    @Bean EastmoneyResearchClient eastmoneyResearchClient(ProviderHttpClient http, Clock clock) {
        return new EastmoneyResearchClient(http, clock);
    }

    @Bean SinaFinanceClient sinaFinanceClient(ProviderHttpClient http, Clock clock) {
        return new SinaFinanceClient(http, clock);
    }

    @Bean CninfoAnnouncementClient cninfoAnnouncementClient(ProviderHttpClient http, Clock clock) {
        return new CninfoAnnouncementClient(http, clock);
    }

    @Bean
    IndustryValuationService industryValuationService(
            EastmoneyResearchClient eastmoney,
            IndustryValuationCalculator calculator,
            @Qualifier("industryClassificationCache")
            Cache<SecurityId, DataSection<List<Sector>>> classificationCache,
            @Qualifier("industryPeerCache")
            Cache<String, DataSection<List<IndustryPeerQuote>>> peerCache) {
        return new IndustryValuationService(
                eastmoney::fetchSectors, eastmoney::fetchIndustryPeers,
                calculator, classificationCache, peerCache);
    }

    @Bean
    ResearchGateway researchGateway(
            TencentMarketDataClient tencent,
            BaiduKlineClient baidu,
            EastmoneyResearchClient eastmoney,
            SinaFinanceClient sina,
            CninfoAnnouncementClient cninfo,
            IndustryValuationService industryValuation,
            @Qualifier("researchReportCache")
            Cache<SecurityId, DataSection<List<ResearchItem>>> researchCache,
            @Qualifier("newsCache")
            Cache<SecurityId, DataSection<List<NewsItem>>> newsCache,
            @Qualifier("announcementCache")
            Cache<SecurityId, DataSection<List<Announcement>>> announcementCache) {
        return new ProviderResearchGateway(
                tencent, baidu, eastmoney, sina, cninfo, industryValuation,
                researchCache, newsCache, announcementCache);
    }

    @Bean
    ResearchAggregationService researchAggregationService(
            ResearchGateway gateway,
            TechnicalAnalysisService technical,
            DataQualityScorer scorer,
            FundFlowSummaryCalculator fundFlowSummaryCalculator,
            Cache<SecurityId, StockResearchSnapshot> cache) {
        return new ResearchAggregationService(gateway, technical, scorer, fundFlowSummaryCalculator, cache);
    }
}
