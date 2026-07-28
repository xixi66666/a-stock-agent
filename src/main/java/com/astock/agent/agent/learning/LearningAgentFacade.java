package com.astock.agent.agent.learning;

import com.astock.agent.agent.learning.mcp.McpToolExecution;
import com.astock.agent.agent.learning.mcp.ResearchMcpToolProvider;
import com.astock.agent.agent.learning.memory.ConversationMemoryService;
import com.astock.agent.agent.learning.rag.RagContext;
import com.astock.agent.agent.learning.rag.ResearchKnowledgeIndexer;
import com.astock.agent.agent.learning.rag.ResearchRetriever;
import com.astock.agent.agent.learning.vector.VectorSearchResult;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.SecurityId;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LearningAgentFacade {

    private final LearningAgentProperties properties;
    private final ConversationMemoryService memory;
    private final ResearchKnowledgeIndexer indexer;
    private final ResearchRetriever retriever;
    private final ResearchMcpToolProvider tools;

    public LearningAgentFacade(
            LearningAgentProperties properties,
            ConversationMemoryService memory,
            ResearchKnowledgeIndexer indexer,
            ResearchRetriever retriever,
            ResearchMcpToolProvider tools) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.memory = memory;
        this.indexer = Objects.requireNonNull(indexer, "indexer");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    public LearningAgentResponse chat(String code, String message, String conversationId) {
        SecurityId security = SecurityId.parse(code);
        String normalizedMessage = requireMessage(message);
        String normalizedConversation = requireConversationId(conversationId);
        if (memory != null && properties.memoryEnabled()) {
            memory.addUserMessage(normalizedConversation, normalizedMessage);
        }

        indexer.index(security, List.of(new ResearchKnowledgeIndexer.ResearchEvidence(
                "offline-learning",
                "股票 " + security.code() + " 的离线学习证据：问题是“" + normalizedMessage + "”。"
                        + "该证据仅用于演示索引、向量检索和来源保留流程。",
                new Provenance("offline-demo", URI.create("https://example.test/offline-learning"),
                        Instant.parse("2026-01-01T00:00:00Z"), Instant.now(), true, null))));

        List<McpToolExecution> executions = new ArrayList<>();
        executions.add(tools.execute("get_research_snapshot", Map.of("code", security.code())));
        executions.add(tools.execute("search_knowledge", Map.of(
                "code", security.code(), "question", normalizedMessage)));
        RagContext context = retriever.retrieve(normalizedMessage, security.code(), properties.topK());
        List<VectorSearchResult> documents = context.documents();
        String answer = "离线学习模式：已处理 " + security.code() + "。\n"
                + (context.formattedPromptContext().isBlank()
                        ? "没有检索到匹配证据。"
                        : "检索证据：\n" + context.formattedPromptContext());
        if (memory != null && properties.memoryEnabled()) {
            memory.addAssistantMessage(normalizedConversation, answer);
        }
        int memoryMessages = memory == null || !properties.memoryEnabled()
                ? 0 : memory.messages(normalizedConversation).size();
        return new LearningAgentResponse(
                normalizedConversation,
                answer,
                "offline-deterministic",
                List.of("TraceAdvisor", "MemoryAdvisor", "RagAdvisor"),
                memoryMessages,
                documents,
                executions,
                "仅供学习研究，不构成投资建议");
    }

    private static String requireMessage(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 2_000) {
            throw new IllegalArgumentException("Message must contain 1 to 2000 Unicode code points");
        }
        return value.trim();
    }

    private static String requireConversationId(String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException("Conversation ID must contain 1 to 100 characters");
        }
        return value.trim();
    }
}
