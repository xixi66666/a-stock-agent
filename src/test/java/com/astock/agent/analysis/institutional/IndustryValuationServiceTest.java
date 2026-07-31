package com.astock.agent.analysis.institutional;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.IndustryPeerQuote;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.model.Sector;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IndustryValuationServiceTest {

    @Test
    void cachesSuccessfulClassificationAndPeerBatch() {
        SecurityId security = SecurityId.parse("600519");
        Sector sector = new Sector("Liquor", "BK0477", BigDecimal.ONE, "600519");
        Provenance source = source("https://example.test/industry-peers");
        AtomicInteger sectorCalls = new AtomicInteger();
        AtomicInteger peerCalls = new AtomicInteger();
        IndustryValuationService service = new IndustryValuationService(
                ignored -> {
                    sectorCalls.incrementAndGet();
                    return DataSection.healthy(List.of(sector), source("https://example.test/sectors"));
                },
                ignored -> {
                    peerCalls.incrementAndGet();
                    return DataSection.healthy(List.of(
                            peer("600519", "30", "3"), peer("000858", "20", "2")), source);
                },
                new IndustryValuationCalculator(), Caffeine.newBuilder().build(), Caffeine.newBuilder().build());

        DataSection<IndustryValuationData> first = service.compare(security);
        DataSection<IndustryValuationData> second = service.compare(security);

        assertThat(first.payload()).isPresent();
        assertThat(first.provenance()).contains(source);
        assertThat(second.payload()).isPresent();
        assertThat(sectorCalls).hasValue(1);
        assertThat(peerCalls).hasValue(1);
    }

    @Test
    void doesNotCacheUnavailablePeerBatch() {
        SecurityId security = SecurityId.parse("600519");
        Sector sector = new Sector("Liquor", "BK0477", BigDecimal.ONE, "600519");
        AtomicInteger peerCalls = new AtomicInteger();
        IndustryValuationService service = new IndustryValuationService(
                ignored -> DataSection.healthy(List.of(sector), source("https://example.test/sectors")),
                ignored -> {
                    peerCalls.incrementAndGet();
                    return DataSection.unavailable("provider blocked");
                },
                new IndustryValuationCalculator(), Caffeine.newBuilder().build(), Caffeine.newBuilder().build());

        assertThat(service.compare(security).payload()).isEmpty();
        assertThat(service.compare(security).payload()).isEmpty();
        assertThat(peerCalls).hasValue(2);
    }

    private static IndustryPeerQuote peer(String code, String pe, String pb) {
        return new IndustryPeerQuote(
                code, code, new BigDecimal(pe), new BigDecimal(pb), new BigDecimal("1000000"));
    }

    private static Provenance source(String url) {
        return new Provenance(
                "fixture", URI.create(url), null, Instant.parse("2026-07-31T00:00:00Z"), false, null);
    }
}
