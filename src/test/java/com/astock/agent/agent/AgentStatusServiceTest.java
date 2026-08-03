package com.astock.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.astock.agent.agent.model.NamedChatClientRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class AgentStatusServiceTest {

    @Test
    void reportsDisabledWhenRoleHasNoRegisteredClient() {
        AgentStatusService service = new AgentStatusService(new NamedChatClientRegistry(Map.of(),
                Map.of("institutional-report", "primary")));

        assertThat(service.status("institutional-report")).isEqualTo(AgentAvailability.DISABLED_CONFIGURATION_MISSING);
        assertThat(service.details()).doesNotContain("api-key");
    }

    @Test
    void reportsReadyForRegisteredRoleWithoutReadingSecrets() {
        ChatClient client = mock(ChatClient.class);
        AgentStatusService service = new AgentStatusService(new NamedChatClientRegistry(
                Map.of("primary", new NamedChatClientRegistry.NamedModel(client, "gpt-test")),
                Map.of("institutional-report", "primary")));

        assertThat(service.status("institutional-report")).isEqualTo(AgentAvailability.READY);
        assertThat(service.details("institutional-report")).doesNotContain("test-secret");
    }
}
