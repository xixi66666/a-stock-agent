package com.astock.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

class LocalModelConfigurationExampleTest {

    private static final Path EXAMPLE = Path.of("config", "application-local.yml.example");

    @Test
    void providesThreeNamedModelTemplatesWithoutRealSecrets() throws Exception {
        String yaml = Files.readString(EXAMPLE);

        assertThat(yaml).contains(
                "app:",
                "models:",
                "primary:",
                "deepseek:",
                "mimo:",
                "https://api.openai.com",
                "https://api.deepseek.com",
                "https://api.xiaomimimo.com/v1",
                "institutional-report: primary",
                "overall-report: deepseek",
                "deepseek-chat",
                "mimo-v2.5-pro",
                "completions-path: \"/chat/completions\"");
        assertThat(yaml).doesNotContainPattern("sk-[A-Za-z0-9_-]{12,}");

        var sources = new YamlPropertySourceLoader()
                .load("local-model-example", new FileSystemResource(EXAMPLE));
        assertThat(sources).hasSize(1);

        PropertySource<?> active = sources.getFirst();
        assertThat(active.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(active.getProperty("spring.ai.model.audio.speech")).isEqualTo("none");
        assertThat(active.getProperty("spring.ai.model.audio.transcription")).isEqualTo("none");
        assertThat(active.getProperty("app.ai.models.primary.base-url")).isEqualTo("https://api.openai.com");
        assertThat(active.getProperty("app.ai.models.primary.api-key"))
                .isEqualTo("replace-with-openai-api-key");
        assertThat(active.getProperty("app.ai.models.deepseek.completions-path"))
                .isEqualTo("/v1/chat/completions");
        assertThat(active.getProperty("app.ai.models.deepseek.model")).isEqualTo("deepseek-chat");
        assertThat(active.getProperty("app.ai.models.mimo.model")).isEqualTo("mimo-v2.5-pro");
        assertThat(active.getProperty("app.ai.models.mimo.completions-path")).isEqualTo("/chat/completions");
        assertThat(active.getProperty("app.ai.roles.institutional-report")).isEqualTo("primary");
        assertThat(active.getProperty("app.ai.roles.overall-report")).isEqualTo("deepseek");
    }
}
