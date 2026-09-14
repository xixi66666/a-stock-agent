package com.astock.agent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ModelConnectivityRegistryTest {

    @Test void recordsOkFailedAndDefaultsToUnknown() {
        var registry = new ModelConnectivityRegistry();
        var now = Instant.parse("2026-09-14T02:00:00Z");

        assertThat(registry.get("deepseek").state()).isEqualTo(ModelConnectivityRegistry.State.UNKNOWN);
        assertThat(registry.get(null).state()).isEqualTo(ModelConnectivityRegistry.State.UNKNOWN);

        registry.recordOk("deepseek", 412L, now);
        assertThat(registry.get("deepseek").state()).isEqualTo(ModelConnectivityRegistry.State.OK);
        assertThat(registry.get("deepseek").latencyMs()).isEqualTo(412L);
        assertThat(registry.get("deepseek").checkedAt()).isEqualTo(now);

        registry.recordFailed("mimo", "MODEL_TIMEOUT", now);
        assertThat(registry.get("mimo").state()).isEqualTo(ModelConnectivityRegistry.State.FAILED);
        assertThat(registry.get("mimo").errorCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(registry.get("mimo").latencyMs()).isNull();

        assertThat(registry.snapshot()).containsOnlyKeys("deepseek", "mimo");
    }
}
