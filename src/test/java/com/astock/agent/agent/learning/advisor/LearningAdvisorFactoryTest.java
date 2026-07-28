package com.astock.agent.agent.learning.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.agent.learning.LearningAgentProperties;
import com.astock.agent.agent.learning.memory.ConversationMemoryService;
import com.astock.agent.agent.learning.rag.ResearchRetriever;
import com.astock.agent.agent.learning.vector.InMemoryResearchVectorStore;
import com.astock.agent.agent.learning.embedding.DeterministicEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

class LearningAdvisorFactoryTest {

    @Test
    void createsTraceMemoryAndRagInStableOrder() {
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(8)
                .build();
        var properties = new LearningAgentProperties(true, true, true, true, true, 8, 4, 16);
        var factory = new LearningAdvisorFactory(
                properties,
                new ConversationMemoryService(memory),
                new ResearchRetriever(new InMemoryResearchVectorStore(new DeterministicEmbeddingModel(16))));

        assertThat(factory.create()).extracting(advisor -> advisor.getName())
                .containsExactly("TraceAdvisor", "MessageChatMemoryAdvisor", "RagAdvisor");
    }
}
