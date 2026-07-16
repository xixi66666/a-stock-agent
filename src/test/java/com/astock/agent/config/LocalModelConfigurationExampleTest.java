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
    void providesThreeOpenAiCompatibleTemplatesWithoutRealSecrets() throws Exception {
        String yaml = Files.readString(EXAMPLE);

        assertThat(yaml).contains(
                "https://api.openai.com",
                "https://api.deepseek.com",
                "https://api.xiaomimimo.com/v1",
                "deepseek-chat",
                "mimo-v2.5-pro",
                "completions-path: \"/chat/completions\"");
        assertThat(yaml).doesNotContainPattern("sk-[A-Za-z0-9_-]{12,}");

        var sources = new YamlPropertySourceLoader()
                .load("local-model-example", new FileSystemResource(EXAMPLE));
        assertThat(sources).hasSize(1);

        PropertySource<?> active = sources.getFirst();
        assertThat(active.getProperty("spring.ai.model.chat")).isEqualTo("openai");
        assertThat(active.getProperty("spring.ai.openai.base-url")).isEqualTo("https://api.openai.com");
        assertThat(active.getProperty("spring.ai.openai.api-key"))
                .isEqualTo("replace-with-openai-api-key");
    }
}
