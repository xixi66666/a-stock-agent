package com.astock.agent.agent.learning;

import com.astock.agent.agent.learning.advisor.LearningAdvisorFactory;
import com.astock.agent.agent.learning.embedding.DeterministicEmbeddingModel;
import com.astock.agent.agent.learning.mcp.ResearchMcpToolProvider;
import com.astock.agent.agent.learning.memory.ConversationMemoryService;
import com.astock.agent.agent.learning.rag.ResearchKnowledgeIndexer;
import com.astock.agent.agent.learning.rag.ResearchRetriever;
import com.astock.agent.agent.learning.vector.InMemoryResearchVectorStore;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.agent.learning", name = "enabled", havingValue = "true")
public class LearningAgentConfiguration {

    @Bean
    DeterministicEmbeddingModel learningEmbeddingModel(LearningAgentProperties properties) {
        return new DeterministicEmbeddingModel(properties.embeddingDimension());
    }

    @Bean
    InMemoryResearchVectorStore learningVectorStore(DeterministicEmbeddingModel embeddingModel) {
        return new InMemoryResearchVectorStore(embeddingModel);
    }

    @Bean
    ResearchKnowledgeIndexer researchKnowledgeIndexer(InMemoryResearchVectorStore vectorStore) {
        return new ResearchKnowledgeIndexer(vectorStore);
    }

    @Bean
    ResearchRetriever researchRetriever(InMemoryResearchVectorStore vectorStore) {
        return new ResearchRetriever(vectorStore);
    }

    @Bean
    ResearchMcpToolProvider researchMcpToolProvider(ResearchRetriever retriever) {
        return new ResearchMcpToolProvider(retriever, code -> Map.of("code", code, "mode", "offline"));
    }

    @Bean
    LearningAdvisorFactory learningAdvisorFactory(
            LearningAgentProperties properties,
            org.springframework.beans.factory.ObjectProvider<ConversationMemoryService> memory,
            ResearchRetriever retriever) {
        return new LearningAdvisorFactory(properties, memory.getIfAvailable(), retriever);
    }

    @Bean
    LearningAgentFacade learningAgentFacade(
            LearningAgentProperties properties,
            org.springframework.beans.factory.ObjectProvider<ConversationMemoryService> memory,
            ResearchKnowledgeIndexer indexer,
            ResearchRetriever retriever,
            ResearchMcpToolProvider tools) {
        return new LearningAgentFacade(properties, memory.getIfAvailable(), indexer, retriever, tools);
    }
}
