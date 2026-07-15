package com.astock.agent.marketdata.provider;

import java.time.Instant;
import java.util.Optional;

public record ProviderAvailability(
        ProviderAvailabilityStatus status,
        boolean available,
        Optional<Instant> lastSuccess,
        Optional<Instant> cooldownUntil) {
}
