package com.astock.agent.agent.finrobot;

import static org.assertj.core.api.Assertions.*;
import com.astock.agent.agent.model.AiModelProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialFinRobotServiceTest {
    Path output = Path.of("target", "finrobot-test-" + java.util.UUID.randomUUID());

    @Test void timeoutCannotBeOverwrittenByLateWorkerSuccess() throws Exception {
        var properties = new OfficialFinRobotProperties("official", "python", output.toString(), Duration.ofMillis(80), 1);
        var models = new AiModelProperties(Map.of("test", new AiModelProperties.Model(true,
                "https://example.org", "fixture", "/v1/chat/completions", "test-model", 0.2)), Map.of());
        try (var service = new OfficialFinRobotService(properties, models, (code, model, dir) -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            return new ObjectMapper().readTree("{\"schema\":\"finrobot-official-v1\",\"ticker\":\"600519\","
                    + "\"upstreamCommit\":\"" + OfficialFinRobotWorker.UPSTREAM + "\",\"status\":\"COMPLETED\"}");
        })) {
            var task = service.start("600519", "test");
            Thread.sleep(500);
            assertThat(service.get(task.id()).status()).isEqualTo("FAILED");
            assertThat(service.get(task.id()).report()).isNull();
            assertThatThrownBy(() -> service.start("../bad", "test")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void taskRunsAsynchronouslyAndKeepsFailureSeparateFromReport() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var properties = new OfficialFinRobotProperties("official", "python", output.toString(), Duration.ofSeconds(5), 1);
        var models = new AiModelProperties(Map.of("test", new AiModelProperties.Model(true,
                "https://example.org", "fixture", "/v1/chat/completions", "test-model", 0.2)),
                Map.of("finrobot-research", "test"));
        try (var service = new OfficialFinRobotService(properties, models, (code, model, dir) -> {
            started.countDown(); release.await();
            throw new IllegalStateException("private upstream error");
        })) {
            var task = service.start("600519", "test");
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(task.status()).isEqualTo("RUNNING");
            assertThatThrownBy(() -> service.start("000001", "test")).hasMessageContaining("繁忙");
            release.countDown();
            for (int i = 0; i < 100 && service.get(task.id()).status().equals("RUNNING"); i++) Thread.sleep(10);
            var failed = service.get(task.id());
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.report()).isNull();
            assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(failed)).doesNotContain("private", "fixture", "apiKey");
        }
    }
}
