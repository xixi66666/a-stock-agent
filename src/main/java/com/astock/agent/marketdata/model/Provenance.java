package com.astock.agent.marketdata.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record Provenance(
        String provider,
        URI sourceUrl,
        Instant providerTimestamp,
        Instant fetchedAt,
        boolean cached,
        String fallbackProvider) {

    public Provenance {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
    }
}
