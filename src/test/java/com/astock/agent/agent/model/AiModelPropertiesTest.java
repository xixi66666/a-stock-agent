package com.astock.agent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AiModelPropertiesTest {

    @Test
    void exposesMultipleModelsAndResolvesRoleToModelEntry() {
        AiModelProperties.Model primary = model("https://api.openai.com", "openai-key", "gpt-4.1");
        AiModelProperties.Model deepSeek = model("https://api.deepseek.com", "deepseek-key", "deepseek-chat");

        AiModelProperties properties = new AiModelProperties(
                Map.of("primary", primary, "deepseek", deepSeek),
                Map.of("institutional-report", "primary", "finrobot-research", "deepseek"));

        assertThat(properties.models()).containsKeys("primary", "deepseek");
        assertThat(properties.modelForRole("finrobot-research"))
                .isPresent()
                .get()
                .satisfies(entry -> {
                    assertThat(entry.getKey()).isEqualTo("deepseek");
                    assertThat(entry.getValue().model()).isEqualTo("deepseek-chat");
                });
    }

    @Test
    void treatsPlaceholderApiKeyAsNotConfigured() {
        AiModelProperties.Model placeholder = model(
                "https://api.deepseek.com", "replace-with-deepseek-api-key", "deepseek-chat");

        assertThat(placeholder.configured()).isFalse();
    }

    @Test
    void usesNonNullMapsAndConstrainsTemperatureToUnitInterval() {
        AiModelProperties properties = new AiModelProperties(null, null);

        assertThat(properties.models()).isEmpty();
        assertThat(properties.roles()).isEmpty();
        assertThat(properties.modelForRole("missing")).isEmpty();
        AiModelProperties.Model highTemperature = new AiModelProperties.Model(
                true, "https://example.test", "key", "", "model", 1.01);
        AiModelProperties.Model lowTemperature = new AiModelProperties.Model(
                true, "https://example.test", "key", null, "model", -0.01);

        assertThat(highTemperature.temperature()).isEqualTo(1.0);
        assertThat(lowTemperature.temperature()).isEqualTo(0.0);
        assertThat(highTemperature.completionsPath()).isEqualTo("/v1/chat/completions");
        assertThat(lowTemperature.completionsPath()).isEqualTo("/v1/chat/completions");
    }

    private static AiModelProperties.Model model(String baseUrl, String apiKey, String model) {
        return new AiModelProperties.Model(true, baseUrl, apiKey, "/chat/completions", model, 0.2);
    }
}
