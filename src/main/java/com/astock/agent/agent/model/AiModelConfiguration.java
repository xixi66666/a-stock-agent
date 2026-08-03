package com.astock.agent.agent.model;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.beans.BeansException;

/** 为每个已配置的 app.ai 模型创建独立 ChatClient，不触发网络请求。 */
@Configuration
public class AiModelConfiguration {

    @Bean
    NamedChatClientRegistry namedChatClientRegistry(
            AiModelProperties properties,
            ObjectProvider<ChatClient.Builder> legacyBuilderProvider,
            Environment environment) {
        Map<String, NamedChatClientRegistry.NamedModel> models = new LinkedHashMap<>();
        properties.models().forEach((id, model) -> {
            if (!model.configured()) {
                return;
            }
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(model.baseUrl())
                    .apiKey(model.apiKey())
                    .completionsPath(model.completionsPath())
                    .build();
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(model.model())
                    .temperature(model.temperature())
                    .build();
            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(options)
                    .build();
            models.put(id, new NamedChatClientRegistry.NamedModel(ChatClient.create(chatModel), model.model()));
        });

        // 兼容 Spring AI 自动配置的单模型 Builder，但不读取或记录 API key。
        boolean legacyPrimaryRegistered = false;
        if (!models.containsKey("primary")) {
            ChatClient.Builder legacy;
            try {
                legacy = legacyBuilderProvider.getIfAvailable();
            } catch (BeansException unavailableLegacyBuilder) {
                legacy = null;
            }
            if (legacy != null) {
                String modelName = environment.getProperty("spring.ai.openai.chat.options.model", "legacy-primary");
                if (modelName == null || modelName.isBlank()) {
                    modelName = "legacy-primary";
                }
                models.put("primary", new NamedChatClientRegistry.NamedModel(legacy.build(), modelName));
                legacyPrimaryRegistered = true;
            }
        }
        Map<String, String> roles = new LinkedHashMap<>(properties.roles());
        if (legacyPrimaryRegistered) {
            roles.putIfAbsent("institutional-report", "primary");
        }
        return new NamedChatClientRegistry(models, roles);
    }
}
