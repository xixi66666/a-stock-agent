package com.astock.agent.agent.model;

import com.astock.agent.agent.AgentAvailability;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.client.ChatClient;

/** 按模型 ID 和业务角色隔离 ChatClient，保证多个模型可以同时存在。 */
public final class NamedChatClientRegistry {

    private final Map<String, NamedModel> models;
    private final Map<String, String> roles;

    public NamedChatClientRegistry(Map<String, NamedModel> models, Map<String, String> roles) {
        this.models = models == null ? Map.of() : Map.copyOf(models);
        this.roles = roles == null ? Map.of() : Map.copyOf(roles);
    }

    public Optional<NamedModel> forRole(String role) {
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(roles.get(role)).map(models::get);
    }

    public AgentAvailability availability(String role) {
        return forRole(role).isPresent()
                ? AgentAvailability.READY
                : AgentAvailability.DISABLED_CONFIGURATION_MISSING;
    }

    public record NamedModel(ChatClient client, String modelName) {
        public NamedModel {
            if (client == null) {
                throw new IllegalArgumentException("client is required");
            }
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalArgumentException("modelName is required");
            }
            modelName = modelName.trim();
        }
    }
}
