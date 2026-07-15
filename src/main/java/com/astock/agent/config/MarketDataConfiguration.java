package com.astock.agent.config;

import com.astock.agent.analysis.DataQualityScorer;
import com.astock.agent.analysis.ProviderResearchGateway;
import com.astock.agent.analysis.ResearchAggregationService;
import com.astock.agent.analysis.ResearchGateway;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.SecurityId;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
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
    ResearchGateway researchGateway(
            TencentMarketDataClient tencent,
            BaiduKlineClient baidu,
            EastmoneyResearchClient eastmoney,
            SinaFinanceClient sina,
            CninfoAnnouncementClient cninfo) {
        return new ProviderResearchGateway(tencent, baidu, eastmoney, sina, cninfo);
    }

    @Bean
    ResearchAggregationService researchAggregationService(
            ResearchGateway gateway,
            TechnicalAnalysisService technical,
            DataQualityScorer scorer,
            Cache<SecurityId, StockResearchSnapshot> cache) {
        return new ResearchAggregationService(gateway, technical, scorer, cache);
    }
}
