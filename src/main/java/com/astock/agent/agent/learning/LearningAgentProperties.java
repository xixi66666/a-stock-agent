package com.astock.agent.agent.learning;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 学习 Agent 的独立开关和边界，默认值保证没有模型密钥时也能离线运行。
 */
@ConfigurationProperties("app.agent.learning")
public record LearningAgentProperties(
        boolean enabled,
        boolean memoryEnabled,
        boolean advisorEnabled,
        boolean ragEnabled,
        boolean mcpEnabled,
        int maxHistoryMessages,
        int topK,
        int embeddingDimension) {

    public LearningAgentProperties {
        maxHistoryMessages = Math.max(2, Math.min(maxHistoryMessages, 100));
        topK = Math.max(1, Math.min(topK, 20));
        embeddingDimension = Math.max(8, Math.min(embeddingDimension, 1024));
    }
}
