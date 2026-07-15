package com.astock.agent.agent;

import org.springframework.core.env.Environment;

public final class AgentStatusService {

    private final Environment environment;

    public AgentStatusService(Environment environment) {
        this.environment = environment;
    }

    public AgentAvailability status() {
        String key = environment.getProperty("spring.ai.openai.api-key", "");
        if (key.isBlank()) {
            return AgentAvailability.DISABLED_CONFIGURATION_MISSING;
        }
        String model = environment.getProperty("spring.ai.model.chat", "none");
        return "openai".equalsIgnoreCase(model)
                ? AgentAvailability.READY
                : AgentAvailability.DISABLED_MODEL_NONE;
    }

    public String details() {
        return switch (status()) {
            case READY -> "OpenAI-compatible chat model is configured";
            case DISABLED_CONFIGURATION_MISSING -> "Local model configuration is missing";
            case DISABLED_MODEL_NONE -> "Chat model is disabled";
        };
    }
}
