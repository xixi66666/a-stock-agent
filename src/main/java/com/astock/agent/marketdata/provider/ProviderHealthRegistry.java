package com.astock.agent.marketdata.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public final class ProviderHealthRegistry {

    private final Duration cooldown;
    private final Clock clock;
    private final Map<ProviderId, Instant> blockedUntil = new EnumMap<>(ProviderId.class);
    private final Map<ProviderId, Instant> lastSuccess = new EnumMap<>(ProviderId.class);

    public ProviderHealthRegistry(Duration cooldown, Clock clock) {
        this.cooldown = cooldown;
        this.clock = clock;
    }

    public synchronized void recordBlocked(ProviderId provider, Instant blockedAt) {
        blockedUntil.put(provider, blockedAt.plus(cooldown));
    }

    public synchronized void recordSuccess(ProviderId provider, Instant succeededAt) {
        lastSuccess.put(provider, succeededAt);
        blockedUntil.remove(provider);
    }

    public synchronized ProviderAvailability availability(ProviderId provider) {
        Instant now = clock.instant();
        Instant until = blockedUntil.get(provider);
        if (until != null && now.isBefore(until)) {
            return new ProviderAvailability(
                    ProviderAvailabilityStatus.COOLDOWN,
                    false,
                    Optional.ofNullable(lastSuccess.get(provider)),
                    Optional.of(until));
        }
        if (until != null) {
            blockedUntil.remove(provider);
        }
        return new ProviderAvailability(
                ProviderAvailabilityStatus.AVAILABLE,
                true,
                Optional.ofNullable(lastSuccess.get(provider)),
                Optional.empty());
    }
}
