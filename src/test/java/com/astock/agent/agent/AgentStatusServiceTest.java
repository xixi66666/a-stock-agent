package com.astock.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AgentStatusServiceTest {

    @Test
    void reportsDisabledWhenKeyIsMissing() {
        AgentStatusService service = new AgentStatusService(
                new MockEnvironment().withProperty("spring.ai.model.chat", "none"));

        assertThat(service.status()).isEqualTo(AgentAvailability.DISABLED_CONFIGURATION_MISSING);
        assertThat(service.details()).doesNotContain("api-key");
    }

    @Test
    void reportsReadyOnlyForOpenAiWithNonBlankKey() {
        AgentStatusService service = new AgentStatusService(new MockEnvironment()
                .withProperty("spring.ai.model.chat", "openai")
                .withProperty("spring.ai.openai.api-key", "test-secret"));

        assertThat(service.status()).isEqualTo(AgentAvailability.READY);
        assertThat(service.details()).doesNotContain("test-secret");
    }
}
