package com.astock.agent.agent.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本地并存的 OpenAI 兼容模型配置及业务角色映射。 */
@ConfigurationProperties("app.ai")
/**
 * {@code app.ai.models} 和 {@code app.ai.roles} 的不可变配置对象。
 *
 * <p>models 描述连接参数，roles 描述业务默认路由。二者分开是为了让总体报告可以按请求
 * 选择模型，同时让研究报告保留稳定的 institutional-report 默认角色。</p>
 */
public record AiModelProperties(Map<String, Model> models, Map<String, String> roles) {

    public AiModelProperties {
        models = immutableCopy(models);
        roles = immutableCopy(roles);
    }

    /** 根据业务角色返回模型名称和配置。角色或模型不存在时返回空。 */
    public Optional<Map.Entry<String, Model>> modelForRole(String role) {
        // 只有角色指向已启用且配置完整的模型时，才返回可用模型。
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        String modelId = roles.get(role);
        if (modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        Model model = models.get(modelId);
        return model == null ? Optional.empty() : Optional.of(Map.entry(modelId, model));
    }

    private static <K, V> Map<K, V> immutableCopy(Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /** 单个模型的 OpenAI 兼容连接参数。 */
    public record Model(
            boolean enabled,
            String baseUrl,
            String apiKey,
            String completionsPath,
            String model,
            double temperature) {

        public Model {
            baseUrl = normalize(baseUrl);
            apiKey = normalize(apiKey);
            completionsPath = normalize(completionsPath);
            if (completionsPath.isBlank()) {
                completionsPath = "/v1/chat/completions";
            }
            model = normalize(model);
            temperature = Math.max(0.0, Math.min(temperature, 1.0));
        }

        /** 配置已启用且具备真实连接参数时才可调用模型。 */
        public boolean configured() {
            return enabled
                    && !baseUrl.isBlank()
                    && !model.isBlank()
                    && !apiKey.isBlank()
                    && !apiKey.toLowerCase(Locale.ROOT).startsWith("replace-with-");
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
