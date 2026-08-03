package com.astock.agent.agent;

import com.astock.agent.agent.model.NamedChatClientRegistry;
import org.springframework.core.env.Environment;

/** 根据命名模型注册表报告各业务角色状态。 */
public final class AgentStatusService {

    private final NamedChatClientRegistry registry;
    private final AgentAvailability legacyStatus;

    public AgentStatusService(NamedChatClientRegistry registry) {
        this.registry = registry == null ? new NamedChatClientRegistry(null, null) : registry;
        this.legacyStatus = null;
    }

    /** 保留旧构造器，供独立 Controller 测试和旧调用方编译；不读取 API key。 */
    public AgentStatusService(Environment environment) {
        this.registry = new NamedChatClientRegistry(null, null);
        String model = environment == null ? "none" : environment.getProperty("spring.ai.model.chat", "none");
        this.legacyStatus = "openai".equalsIgnoreCase(model)
                ? AgentAvailability.READY
                : AgentAvailability.DISABLED_CONFIGURATION_MISSING;
    }

    public AgentAvailability status() {
        return legacyStatus != null ? legacyStatus : status("institutional-report");
    }

    public AgentAvailability status(String role) {
        return registry.availability(role);
    }

    public String details() {
        return detailsFor(status());
    }

    public String details(String role) {
        return detailsFor(status(role));
    }

    private static String detailsFor(AgentAvailability availability) {
        return switch (availability) {
            case READY -> "OpenAI-compatible chat model is configured";
            case DISABLED_CONFIGURATION_MISSING -> "Local model configuration is missing";
            case DISABLED_MODEL_NONE -> "Chat model is disabled";
        };
    }
}
