package com.astock.agent.web;

import com.astock.agent.agent.model.ModelConnectivityRegistry;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 统一模型目录：安全字段 + 启动探测的连通性状态。 */
@RestController
@RequestMapping("/api/ai")
public final class AiModelController {

    /** 目录默认模型以 FinRobot 主链路角色为准；未配置该角色时全部标记为非默认。 */
    private static final String DEFAULT_ROLE = "finrobot-research";

    private final NamedChatClientRegistry registry;
    private final ModelConnectivityRegistry connectivity;

    public AiModelController(NamedChatClientRegistry registry, ModelConnectivityRegistry connectivity) {
        this.registry = registry;
        this.connectivity = connectivity;
    }

    @GetMapping("/models")
    public ModelsResponse models(
            @RequestParam(name = "role", defaultValue = DEFAULT_ROLE) String defaultRole) {
        Map<String, List<String>> rolesByModel = new HashMap<>();
        registry.roles().forEach((role, modelId) ->
                rolesByModel.computeIfAbsent(modelId, ignored -> new ArrayList<>()).add(role));
        var models = registry.availableModels(defaultRole).stream()
                .map(reference -> new ModelStatus(reference.id(), reference.modelName(),
                        reference.defaultModel(),
                        List.copyOf(rolesByModel.getOrDefault(reference.id(), List.of())),
                        connectivity.get(reference.id())))
                .toList();
        return new ModelsResponse(models);
    }

    public record ModelsResponse(List<ModelStatus> models) {
        public ModelsResponse {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    public record ModelStatus(String id, String modelName, boolean defaultModel,
            List<String> roles, ModelConnectivityRegistry.Connectivity connectivity) {
        public ModelStatus {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }
}
