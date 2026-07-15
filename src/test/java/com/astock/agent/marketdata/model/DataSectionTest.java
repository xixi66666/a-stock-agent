package com.astock.agent.marketdata.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataSectionTest {

    private static final Provenance SOURCE = new Provenance(
            "Tencent Finance",
            URI.create("https://qt.gtimg.cn/q=sh600519"),
            Instant.parse("2026-07-15T07:00:00Z"),
            Instant.parse("2026-07-15T07:00:01Z"),
            false,
            null);

    @Test
    void healthySectionRequiresPayloadAndProvenance() {
        DataSection<String> section = DataSection.healthy("payload", SOURCE);

        assertThat(section.status()).isEqualTo(SectionStatus.HEALTHY);
        assertThat(section.payload()).contains("payload");
        assertThat(section.provenance()).contains(SOURCE);
        assertThat(section.issues()).isEmpty();

        assertThatThrownBy(() -> DataSection.healthy(null, SOURCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataSection.healthy("payload", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unavailableSectionPreservesFailureWithoutInventingPayload() {
        DataSection<String> section = DataSection.unavailable("HTTP 403 provider cooldown");

        assertThat(section.status()).isEqualTo(SectionStatus.UNAVAILABLE);
        assertThat(section.payload()).isEmpty();
        assertThat(section.provenance()).isEmpty();
        assertThat(section.issues()).containsExactly("HTTP 403 provider cooldown");
    }

    @Test
    void degradedSectionKeepsUsablePayloadAndValidationIssues() {
        DataSection<String> section = DataSection.degraded(
                "fallback payload", SOURCE, List.of("primary source unavailable"));

        assertThat(section.status()).isEqualTo(SectionStatus.DEGRADED);
        assertThat(section.payload()).contains("fallback payload");
        assertThat(section.issues()).containsExactly("primary source unavailable");
    }
}
