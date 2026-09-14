package com.astock.agent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.agent.report.ModelFailureClassifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import static org.mockito.Mockito.mock;

class ModelConnectivityProbeTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-14T02:00:00Z"), ZoneOffset.UTC);

    @Test void probesAllModelsSeriallyAndRecordsOkState() {
        var states = new ModelConnectivityRegistry();
        var registry = new NamedChatClientRegistry(
                Map.of("deepseek", named("deepseek-chat"), "mimo", named("mimo-v2")), Map.of());
        var active = new AtomicInteger();
        var maxActive = new AtomicInteger();
        var probe = new ModelConnectivityProbe(registry, states, new ModelFailureClassifier(), clock,
                Duration.ofSeconds(2), client -> {
                    maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    active.decrementAndGet();
                    return "pong";
                });

        probe.probeAll();

        assertThat(maxActive).hasValue(1);
        assertThat(states.get("deepseek").state()).isEqualTo(ModelConnectivityRegistry.State.OK);
        assertThat(states.get("deepseek").latencyMs()).isNotNull();
        assertThat(states.get("deepseek").checkedAt()).isEqualTo(clock.instant());
        assertThat(states.get("mimo").state()).isEqualTo(ModelConnectivityRegistry.State.OK);
    }

    @Test void recordsFailureClassificationAndTreatsBlankSuccessAsReachable() {
        var states = new ModelConnectivityRegistry();
        var failing = named("failing");
        var empty = named("empty");
        var registry = new NamedChatClientRegistry(Map.of("a-failing", failing, "b-empty", empty), Map.of());
        var probe = new ModelConnectivityProbe(registry, states, new ModelFailureClassifier(), clock,
                Duration.ofSeconds(2), client -> {
                    if (client == failing.client()) {
                        throw new RuntimeException("connection refused to model endpoint");
                    }
                    return "  ";
                });

        probe.probeAll();

        assertThat(states.get("a-failing").state()).isEqualTo(ModelConnectivityRegistry.State.FAILED);
        assertThat(states.get("a-failing").errorCode()).isEqualTo("MODEL_CONNECTION_FAILED");
        // 端点已应答即算连通；空文本可能来自推理模型或短输出，不作为不可用依据。
        assertThat(states.get("b-empty").state()).isEqualTo(ModelConnectivityRegistry.State.OK);
    }

    @Test void timesOutHungModelsWithoutStoppingOthers() {
        var states = new ModelConnectivityRegistry();
        var hung = named("hung");
        var healthy = named("healthy");
        var registry = new NamedChatClientRegistry(Map.of("a-hung", hung, "b-healthy", healthy), Map.of());
        var probe = new ModelConnectivityProbe(registry, states, new ModelFailureClassifier(), clock,
                Duration.ofMillis(100), client -> {
                    if (client == hung.client()) {
                        try {
                            Thread.sleep(5_000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return "pong";
                });

        probe.probeAll();

        assertThat(states.get("a-hung").state()).isEqualTo(ModelConnectivityRegistry.State.FAILED);
        assertThat(states.get("a-hung").errorCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(states.get("b-healthy").state()).isEqualTo(ModelConnectivityRegistry.State.OK);
    }

    @Test void emptyCatalogProbesNothing() {
        var states = new ModelConnectivityRegistry();
        var calls = new ArrayList<String>();
        var probe = new ModelConnectivityProbe(new NamedChatClientRegistry(Map.of(), Map.of()), states,
                new ModelFailureClassifier(), clock, Duration.ofSeconds(1),
                client -> { calls.add("call"); return "pong"; });
        probe.probeAll();
        assertThat(calls).isEmpty();
        assertThat(states.snapshot()).isEmpty();
    }

    private static NamedChatClientRegistry.NamedModel named(String modelName) {
        return new NamedChatClientRegistry.NamedModel(mock(ChatClient.class), modelName);
    }
}
