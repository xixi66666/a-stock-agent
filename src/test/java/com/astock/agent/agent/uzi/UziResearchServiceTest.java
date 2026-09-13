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

class UziResearchServiceTest {

    @Test
    void completesAnAsyncTaskAndKeepsTheStructuredBundle() throws Exception {
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
            UziResearchService.Task started = service.start("600519", "deep", "F");
            UziResearchService.Task completed = await(service, started.id());

            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.bundle().ticker()).isEqualTo("600519");
            assertThat(completed.bundle().structured()).containsKey("companyProfile");
            assertThat(completed.bundle().sources()).hasSize(1);
            assertThat(completed.reportPath()).isNull();
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
}
