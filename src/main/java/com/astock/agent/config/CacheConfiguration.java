package com.astock.agent.config;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.Announcement;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.IndustryPeerQuote;
import com.astock.agent.marketdata.model.NewsItem;
import com.astock.agent.marketdata.model.ResearchItem;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.model.Sector;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/**
 * Caffeine 缓存配置。
 *
 * <p>缓存的是带抓取时间和 provenance 的完整结果；刷新会使快照失效，不能把缓存命中误认为
 * Provider 刚刚返回了实时数据。</p>
 */
public class CacheConfiguration {

    @Bean
    Cache<SecurityId, StockResearchSnapshot> researchSnapshotCache() {
        return Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofSeconds(15))
                .build();
    }

    @Bean("industryClassificationCache")
    Cache<SecurityId, DataSection<List<Sector>>> industryClassificationCache() {
        return Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofHours(24)).build();
    }

    @Bean("industryPeerCache")
    Cache<String, DataSection<List<IndustryPeerQuote>>> industryPeerCache() {
        return Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofMinutes(15)).build();
    }

    @Bean("researchReportCache")
    Cache<SecurityId, DataSection<List<ResearchItem>>> researchReportCache() {
        return Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofHours(6)).build();
    }

    @Bean("newsCache")
    Cache<SecurityId, DataSection<List<NewsItem>>> newsCache() {
        return Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofMinutes(30)).build();
    }

    @Bean("announcementCache")
    Cache<SecurityId, DataSection<List<Announcement>>> announcementCache() {
        return Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofMinutes(30)).build();
    }
}
