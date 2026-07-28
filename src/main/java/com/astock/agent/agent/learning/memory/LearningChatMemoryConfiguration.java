package com.astock.agent.agent.learning.memory;

import com.astock.agent.agent.learning.LearningAgentProperties;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.agent.learning", name = "enabled", havingValue = "true")
public class LearningChatMemoryConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.agent.learning", name = "memory-enabled", havingValue = "true", matchIfMissing = true)
    ChatMemory learningChatMemory(LearningAgentProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(properties.maxHistoryMessages())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.agent.learning", name = "memory-enabled", havingValue = "true", matchIfMissing = true)
    ConversationMemoryService conversationMemoryService(ChatMemory learningChatMemory) {
        return new ConversationMemoryService(learningChatMemory);
    }
}
