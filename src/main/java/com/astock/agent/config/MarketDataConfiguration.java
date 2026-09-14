package com.astock.agent.config;

import com.astock.agent.analysis.DataQualityScorer;
import com.astock.agent.analysis.FundFlowSummaryCalculator;
import com.astock.agent.analysis.ProviderResearchGateway;
import com.astock.agent.analysis.ResearchAggregationService;
import com.astock.agent.analysis.ResearchGateway;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.analysis.candlestick.CandlestickAnalysisService;
import com.astock.agent.knowledge.BookKnowledgeService;
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
import com.astock.agent.marketdata.provider.BenchmarkDataGateway;
import com.astock.agent.marketdata.provider.IndexQuoteGateway;
import com.astock.agent.marketdata.provider.baidu.BaiduKlineClient;
import com.astock.agent.marketdata.provider.cninfo.CninfoAnnouncementClient;
import com.astock.agent.marketdata.provider.eastmoney.EastmoneyResearchClient;
import com.astock.agent.marketdata.provider.sina.SinaFinanceClient;
import com.astock.agent.marketdata.provider.tencent.TencentMarketDataClient;
import com.astock.agent.marketdata.provider.tencent.TencentBenchmarkDataGateway;
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

    @Bean
    com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient hithinkFinanceClient(
            ProviderHttpClient http, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${hithink.enabled:false}") boolean enabled,
            @org.springframework.beans.factory.annotation.Value("${hithink.api-key:}") String apiKey) {
        return new com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient(http, clock,
                enabled ? apiKey : "", java.net.URI.create("https://fuyao.aicubes.cn"));
    }

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
    @Bean CandlestickAnalysisService candlestickAnalysisService(BarSeriesFactory factory, Clock clock,
            BookKnowledgeService knowledge) {
        return new CandlestickAnalysisService(factory, clock, knowledge);
    }
    @Bean DataQualityScorer dataQualityScorer() { return new DataQualityScorer(); }
    @Bean FundFlowSummaryCalculator fundFlowSummaryCalculator() { return new FundFlowSummaryCalculator(); }
    @Bean IndustryValuationCalculator industryValuationCalculator() { return new IndustryValuationCalculator(); }

    @Bean
    TencentMarketDataClient tencentMarketDataClient(
            ProviderHttpClient http, TencentResponseParser parser, Clock clock) {
        return new TencentMarketDataClient(http, parser, clock);
    }

    @Bean
    BenchmarkDataGateway benchmarkDataGateway(
            ProviderHttpClient http, TencentResponseParser parser, Clock clock,
            com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient hithink) {
        return new com.astock.agent.analysis.HithinkBenchmarkGateway(hithink,
                new TencentBenchmarkDataGateway(http, parser, clock));
    }

    @Bean
    IndexQuoteGateway indexQuoteGateway(
            com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient hithink,
            BenchmarkDataGateway benchmarkDataGateway) {
        return new com.astock.agent.analysis.HithinkIndexQuoteGateway(hithink, benchmarkDataGateway);
    }

    @Bean
    com.astock.agent.analysis.MarketIndexService marketIndexService(IndexQuoteGateway gateway) {
        return new com.astock.agent.analysis.MarketIndexService(gateway);
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
            com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient hithink,
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
        var providerGateway = new ProviderResearchGateway(
                tencent, baidu, eastmoney, sina, cninfo, industryValuation,
                researchCache, newsCache, announcementCache);
        // 行情字段冗余：腾讯补齐后仍缺的字段由东财、新浪继续补；同花顺整体不可用按同序整段回退。
        return new com.astock.agent.analysis.HithinkResearchGateway(providerGateway, hithink, List.of(
                providerGateway::quote, eastmoney::fetchQuote, sina::fetchQuote));
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
