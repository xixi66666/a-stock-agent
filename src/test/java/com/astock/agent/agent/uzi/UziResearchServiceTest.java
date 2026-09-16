package com.astock.agent.agent.uzi;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.agent.model.NamedChatClientRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class UziResearchServiceTest {

    @Test
    void completesAnAsyncTaskAndKeepsTheStructuredBundle(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        Path temp = Path.of("target", "uzi-test-" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(temp.resolve("UZI-Skill"));
        Files.writeString(temp.resolve("UZI-Skill/run.py"), "# test fixture");
        UziProperties properties = new UziProperties(
                temp.resolve("UZI-Skill").toString(), "java", temp.resolve("reports").toString(),
                Duration.ofSeconds(5), 1);
        UziResearchBundle bundle = new UziResearchBundle(
                "uzi-bundle-v1", "600519", "2026-09-13T00:00:00Z", null, null, null, null,
                java.util.Map.of("companyProfile", java.util.Map.of("industry", "食品饮料")),
                List.of(new UziSourceReference("0_basic", "CNINFO", "https://example.test/basic", "2026-09-13")),
                List.of(), temp.resolve("reports/600519/index.html").toString());
        UziResearchService service = new UziResearchService(properties,
                new NamedChatClientRegistry(null, null),
                (code, depth, school, modelName, outputDir) -> bundle);
        try {
            org.slf4j.MDC.put("requestId", "request-uzi-test");
            org.slf4j.MDC.put("interactionId", "click-uzi-test");
            UziResearchService.Task started;
            try { started = service.start("600519", "deep", "F"); }
            finally { org.slf4j.MDC.clear(); }
            UziResearchService.Task completed = await(service, started.id());

            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.bundle().ticker()).isEqualTo("600519");
            assertThat(completed.bundle().structured()).containsKey("companyProfile");
            assertThat(completed.bundle().sources()).hasSize(1);
            assertThat(completed.reportPath()).isNull();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(output.getOut()).contains("TASK_CREATED", "TASK_START", "TASK_COMPLETED",
                            "module=uzi", "taskId=" + started.id(), "requestId=request-uzi-test",
                            "interactionId=click-uzi-test"));
        } finally {
            service.close();
            try (var paths = Files.walk(temp)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    private static UziResearchService.Task await(UziResearchService service, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        UziResearchService.Task task;
        do {
            task = service.get(id);
            if (!"RUNNING".equals(task.status())) return task;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return task;
    }

    @Test
    void explicitModelIdIsPassedToTheWorker() throws Exception {
        Path temp = Path.of("target", "uzi-test-" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(temp.resolve("UZI-Skill"));
        Files.writeString(temp.resolve("UZI-Skill/run.py"), "# test fixture");
        UziProperties properties = new UziProperties(
                temp.resolve("UZI-Skill").toString(), "java", temp.resolve("reports").toString(),
                Duration.ofSeconds(5), 1);
        var seenModel = new java.util.concurrent.atomic.AtomicReference<String>();
        var bundle = new UziResearchBundle(
                "uzi-bundle-v1", "600519", "2026-09-13T00:00:00Z", null, null, null, null,
                java.util.Map.of(), List.of(), List.of(),
                temp.resolve("reports/600519/index.html").toString());
        var registry = new NamedChatClientRegistry(
                java.util.Map.of(
                        "deepseek", new NamedChatClientRegistry.NamedModel(
                                org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.class),
                                "deepseek-chat"),
                        "mimo", new NamedChatClientRegistry.NamedModel(
                                org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.class),
                                "mimo-v2")),
                java.util.Map.of("uzi-research", "deepseek"));
        UziResearchService service = new UziResearchService(properties, registry,
                (code, depth, school, modelName, outputDir) -> {
                    seenModel.set(modelName);
                    return bundle;
                });
        try {
            UziResearchService.Task started = service.start("600519", "deep", "F", "mimo");
            await(service, started.id());
            assertThat(started.modelName()).isEqualTo("mimo-v2");
            assertThat(seenModel.get()).isEqualTo("mimo-v2");
        } finally {
            service.close();
            try (var paths = Files.walk(temp)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    @Test
    void unknownExplicitModelIdIsRejected() {
        UziProperties properties = new UziProperties(
                "target/uzi-missing", "java", "target/uzi-reports-missing", Duration.ofSeconds(1), 1);
        UziResearchService service = new UziResearchService(properties,
                new NamedChatClientRegistry(java.util.Map.of(), java.util.Map.of()),
                (code, depth, school, modelName, outputDir) -> {
                    throw new AssertionError("未知模型不得启动 worker");
                });
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.start("600519", "deep", "F", "ghost"))
                    .isInstanceOf(com.astock.agent.agent.model.ModelNotAvailableException.class);
        } finally {
            service.close();
        }
    }
}
