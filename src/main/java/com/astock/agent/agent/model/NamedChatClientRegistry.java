package com.astock.agent.agent.model;

import com.astock.agent.agent.AgentAvailability;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.client.ChatClient;

/** 按模型 ID 和业务角色隔离 ChatClient，保证多个模型可以同时存在。 */
/**
 * 按模型 ID 和业务角色隔离 ChatClient 的注册表。
 *
 * <p>配置层先创建多个命名模型，角色再把业务能力映射到默认模型。浏览器只能看到
 * {@link ModelReference}，不会拿到 API Key、Base URL 或连接参数。这样研究报告和总体报告
 * 可以同时使用不同模型，又不会把路由逻辑散落到 Controller。</p>
 */
public final class NamedChatClientRegistry {

    private final Map<String, NamedModel> models;
    private final Map<String, String> roles;

    public NamedChatClientRegistry(Map<String, NamedModel> models, Map<String, String> roles) {
        this.models = models == null ? Map.of() : Map.copyOf(models);
        this.roles = roles == null ? Map.of() : Map.copyOf(roles);
    }

    public Optional<NamedModel> forRole(String role) {
        // 角色映射缺失时返回 empty，让上层选择确定性降级，而不是构造半配置 ChatClient。
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(roles.get(role)).map(models::get);
    }

    public Optional<NamedModel> byId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(models.get(modelId.trim()));
    }

    public Optional<String> modelIdForRole(String role) {
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(roles.get(role))
                .filter(models::containsKey);
    }

    public List<ModelReference> availableModels(String defaultRole) {
        // 只序列化安全目录；NamedModel 内部的 ChatClient 永远不进入 API 响应。
        String defaultId = modelIdForRole(defaultRole).orElse(null);
        return models.entrySet().stream()
                .map(entry -> new ModelReference(
                        entry.getKey(),
                        entry.getValue().modelName(),
                        entry.getKey().equals(defaultId)))
                .sorted(Comparator.comparing(ModelReference::id))
                .toList();
    }

    public AgentAvailability availability(String role) {
        return forRole(role).isPresent()
                ? AgentAvailability.READY
                : AgentAvailability.DISABLED_CONFIGURATION_MISSING;
    }

    /** 只读角色映射；用于把角色归属并入统一模型目录。 */
    public Map<String, String> roles() {
        return roles;
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

    public record ModelReference(String id, String modelName, boolean defaultModel) {
    }
}
