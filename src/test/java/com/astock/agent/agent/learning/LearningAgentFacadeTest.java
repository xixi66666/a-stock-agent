package com.astock.agent.agent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.astock.agent.agent.learning.embedding.DeterministicEmbeddingModel;
import com.astock.agent.agent.learning.mcp.ResearchMcpToolProvider;
import com.astock.agent.agent.learning.memory.ConversationMemoryService;
import com.astock.agent.agent.learning.rag.ResearchKnowledgeIndexer;
import com.astock.agent.agent.learning.rag.ResearchRetriever;
import com.astock.agent.agent.learning.vector.InMemoryResearchVectorStore;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

class LearningAgentFacadeTest {

    @Test
    void runsOfflineMemoryRagAndMcpPipeline() {
        var vectorStore = new InMemoryResearchVectorStore(new DeterministicEmbeddingModel(16));
        var memory = new ConversationMemoryService(MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(12)
                .build());
        var retriever = new ResearchRetriever(vectorStore);
        var tools = new ResearchMcpToolProvider(retriever, code -> Map.of("code", code, "mode", "offline"));
        var facade = new LearningAgentFacade(
                new LearningAgentProperties(true, true, true, true, true, 12, 4, 16),
                memory,
                new ResearchKnowledgeIndexer(vectorStore),
                retriever,
                tools);

        LearningAgentResponse first = facade.chat("600519", "解释盈利", "demo");
        LearningAgentResponse second = facade.chat("600519", "继续说明", "demo");

        assertThat(first.modelUsed()).isEqualTo("offline-deterministic");
        assertThat(first.answer()).contains("600519");
        assertThat(first.retrievedDocuments()).isNotEmpty();
        assertThat(first.toolExecutions()).anyMatch(execution -> execution.success());
        assertThat(first.advisorTrace()).contains("TraceAdvisor", "MemoryAdvisor", "RagAdvisor");
        assertThat(first.disclaimer()).isEqualTo("仅供学习研究，不构成投资建议");
        assertThat(second.memoryMessages()).isGreaterThan(first.memoryMessages());
    }

    @Test
    void rejectsInvalidCodeAndOversizedMessage() {
        var vectorStore = new InMemoryResearchVectorStore(new DeterministicEmbeddingModel(16));
        var memory = new ConversationMemoryService(MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(4).build());
        var retriever = new ResearchRetriever(vectorStore);
        var facade = new LearningAgentFacade(
                new LearningAgentProperties(true, true, true, true, true, 4, 2, 16), memory,
                new ResearchKnowledgeIndexer(vectorStore), retriever,
                new ResearchMcpToolProvider(retriever, code -> Map.of("code", code)));

        assertThatThrownBy(() -> facade.chat("ABC", "问题", "demo"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facade.chat("600519", "x".repeat(2_001), "demo"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
