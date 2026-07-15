package com.astock.agent.marketdata.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ProviderHealthRegistryTest {

    private static final Instant NOW = Instant.parse("2026-07-15T02:00:00Z");

    @Test
    void http403StartsCooldownWithoutRetry() {
        ProviderHealthRegistry registry = new ProviderHealthRegistry(
                Duration.ofMinutes(30), Clock.fixed(NOW, ZoneOffset.UTC));

        registry.recordBlocked(ProviderId.EASTMONEY, NOW);

        assertThat(registry.availability(ProviderId.EASTMONEY).status())
                .isEqualTo(ProviderAvailabilityStatus.COOLDOWN);
        assertThat(registry.availability(ProviderId.EASTMONEY).available()).isFalse();
    }

    @Test
    void successfulCallClearsFailureAndRecordsTimestamp() {
        ProviderHealthRegistry registry = new ProviderHealthRegistry(
                Duration.ofMinutes(30), Clock.fixed(NOW, ZoneOffset.UTC));
        registry.recordBlocked(ProviderId.EASTMONEY, NOW.minusSeconds(60));

        registry.recordSuccess(ProviderId.EASTMONEY, NOW);

        ProviderAvailability availability = registry.availability(ProviderId.EASTMONEY);
        assertThat(availability.status()).isEqualTo(ProviderAvailabilityStatus.AVAILABLE);
        assertThat(availability.lastSuccess()).contains(NOW);
        assertThat(availability.available()).isTrue();
    }
}
