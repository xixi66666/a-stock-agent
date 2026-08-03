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
                Map.of("institutional-report", "primary", "overall-report", "deepseek"));

        assertThat(registry.forRole("institutional-report").orElseThrow().client()).isSameAs(primary);
        assertThat(registry.forRole("overall-report").orElseThrow().client()).isSameAs(deepseek);
        assertThat(registry.forRole("overall-report").orElseThrow().modelName()).isEqualTo("deepseek-chat");
    }

    @Test
    void missingRoleIsUnavailableWithoutDisablingOtherRoles() {
        NamedChatClientRegistry registry = new NamedChatClientRegistry(Map.of(),
                Map.of("institutional-report", "primary", "overall-report", "deepseek"));

        assertThat(registry.forRole("overall-report")).isEmpty();
        assertThat(registry.availability("overall-report")).isEqualTo(AgentAvailability.DISABLED_CONFIGURATION_MISSING);
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
