package com.astock.agent.analysis.institutional;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.IndustryPeerQuote;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.model.Sector;
import com.github.benmanes.caffeine.cache.Cache;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class IndustryValuationService {

    private final Function<SecurityId, DataSection<List<Sector>>> sectorLookup;
    private final Function<Sector, DataSection<List<IndustryPeerQuote>>> peerLookup;
    private final IndustryValuationCalculator calculator;
    private final Cache<SecurityId, DataSection<List<Sector>>> classificationCache;
    private final Cache<String, DataSection<List<IndustryPeerQuote>>> peerCache;

    public IndustryValuationService(
            Function<SecurityId, DataSection<List<Sector>>> sectorLookup,
            Function<Sector, DataSection<List<IndustryPeerQuote>>> peerLookup,
            IndustryValuationCalculator calculator,
            Cache<SecurityId, DataSection<List<Sector>>> classificationCache,
            Cache<String, DataSection<List<IndustryPeerQuote>>> peerCache) {
        this.sectorLookup = sectorLookup;
        this.peerLookup = peerLookup;
        this.calculator = calculator;
        this.classificationCache = classificationCache;
        this.peerCache = peerCache;
    }

    public DataSection<IndustryValuationData> compare(SecurityId security) {
        DataSection<List<Sector>> sectors = cachedClassification(security);
        if (sectors.payload().isEmpty() || sectors.payload().orElseThrow().isEmpty()) {
            return DataSection.unavailable(issue(sectors, "Industry classification is unavailable"));
        }
        Sector industry = sectors.payload().orElseThrow().stream()
                .filter(value -> value.code() != null && !value.code().isBlank()
                        && value.name() != null && !value.name().isBlank())
                .findFirst()
                .orElse(null);
        if (industry == null) {
            return DataSection.unavailable("Industry classification contains no usable sector");
        }
        DataSection<List<IndustryPeerQuote>> peers = cachedPeers(industry);
        if (peers.payload().isEmpty() || peers.payload().orElseThrow().isEmpty()) {
            return DataSection.unavailable(issue(peers, "Industry peer batch is unavailable"));
        }
        IndustryValuationCalculation calculation = calculator.calculate(
                industry.code(), industry.name(), security.code(), peers.payload().orElseThrow());
        return sectionLike(peers, calculation.data(), calculation.issues());
    }

    private DataSection<List<Sector>> cachedClassification(SecurityId security) {
        DataSection<List<Sector>> cached = classificationCache.getIfPresent(security);
        if (cached != null) {
            return markCached(cached);
        }
        DataSection<List<Sector>> loaded = sectorLookup.apply(security);
        if (cacheable(loaded)) {
            classificationCache.put(security, loaded);
        }
        return loaded;
    }

    private DataSection<List<IndustryPeerQuote>> cachedPeers(Sector industry) {
        DataSection<List<IndustryPeerQuote>> cached = peerCache.getIfPresent(industry.code());
        if (cached != null) {
            return markCached(cached);
        }
        DataSection<List<IndustryPeerQuote>> loaded = peerLookup.apply(industry);
        if (cacheable(loaded)) {
            peerCache.put(industry.code(), loaded);
        }
        return loaded;
    }

    private static boolean cacheable(DataSection<? extends List<?>> section) {
        return section.payload().map(values -> !values.isEmpty()).orElse(false)
                && section.provenance().isPresent();
    }

    private static String issue(DataSection<?> section, String fallback) {
        return section.issues().isEmpty() ? fallback : section.issues().getFirst();
    }

    private static <T> DataSection<T> sectionLike(
            DataSection<?> source, T payload, List<String> additionalIssues) {
        Provenance provenance = source.provenance().orElseThrow();
        List<String> issues = new ArrayList<>(source.issues());
        issues.addAll(additionalIssues);
        return switch (source.status()) {
            case HEALTHY -> issues.isEmpty()
                    ? DataSection.healthy(payload, provenance)
                    : DataSection.degraded(payload, provenance, issues);
            case DEGRADED -> DataSection.degraded(payload, provenance, issues);
            case STALE -> DataSection.stale(payload, provenance, issues);
            case UNVERIFIED -> DataSection.unverified(payload, provenance, issues);
            case UNAVAILABLE -> throw new IllegalArgumentException("Unavailable source has no payload");
        };
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
