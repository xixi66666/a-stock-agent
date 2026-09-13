package com.astock.agent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.astock.agent.agent.AgentAvailability;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class NamedChatClientRegistryTest {

    @Test
    void resolvesIndependentClientsByRole() {
        ChatClient primary = mock(ChatClient.class);
        ChatClient deepseek = mock(ChatClient.class);
        NamedChatClientRegistry registry = new NamedChatClientRegistry(
                Map.of(
                        "primary", new NamedChatClientRegistry.NamedModel(primary, "gpt-test"),
                        "deepseek", new NamedChatClientRegistry.NamedModel(deepseek, "deepseek-chat")),
                Map.of("institutional-report", "primary", "finrobot-research", "deepseek"));

        assertThat(registry.forRole("institutional-report").orElseThrow().client()).isSameAs(primary);
        assertThat(registry.forRole("finrobot-research").orElseThrow().client()).isSameAs(deepseek);
        assertThat(registry.forRole("finrobot-research").orElseThrow().modelName()).isEqualTo("deepseek-chat");
    }

    @Test
    void missingRoleIsUnavailableWithoutDisablingOtherRoles() {
        NamedChatClientRegistry registry = new NamedChatClientRegistry(Map.of(),
                Map.of("institutional-report", "primary", "finrobot-research", "deepseek"));

        assertThat(registry.forRole("finrobot-research")).isEmpty();
        assertThat(registry.availability("finrobot-research")).isEqualTo(AgentAvailability.DISABLED_CONFIGURATION_MISSING);
    }

    @Test
    void exposesSafeCatalogAndMarksRoleDefault() {
        ChatClient primary = mock(ChatClient.class);
        ChatClient mimo = mock(ChatClient.class);
        NamedChatClientRegistry registry = new NamedChatClientRegistry(
                Map.of(
                        "primary", new NamedChatClientRegistry.NamedModel(primary, "gpt-5"),
                        "mimo", new NamedChatClientRegistry.NamedModel(mimo, "mimo-v2.5-pro")),
                Map.of("finrobot-research", "mimo"));

        assertThat(registry.availableModels("finrobot-research"))
                .extracting(
                        NamedChatClientRegistry.ModelReference::id,
                        NamedChatClientRegistry.ModelReference::modelName,
                        NamedChatClientRegistry.ModelReference::defaultModel)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("mimo", "mimo-v2.5-pro", true),
                        org.assertj.core.groups.Tuple.tuple("primary", "gpt-5", false));
        assertThat(registry.modelIdForRole("finrobot-research")).contains("mimo");
    }

    @Test
    void resolvesOnlyRegisteredModelsById() {
        ChatClient client = mock(ChatClient.class);
        NamedChatClientRegistry registry = new NamedChatClientRegistry(
                Map.of("primary", new NamedChatClientRegistry.NamedModel(client, "gpt-5")),
                Map.of());

        assertThat(registry.byId(" primary ").orElseThrow().client()).isSameAs(client);
        assertThat(registry.byId("missing")).isEmpty();
        assertThat(registry.byId(" ")).isEmpty();
    }

    @Test
    void namedModelRejectsNullOrBlankModelName() {
        ChatClient client = mock(ChatClient.class);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new NamedChatClientRegistry.NamedModel(client, null));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new NamedChatClientRegistry.NamedModel(client, "  "));
    }
}
