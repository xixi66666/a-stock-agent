package com.astock.agent.agent.learning;

import com.astock.agent.agent.learning.mcp.McpToolExecution;
import com.astock.agent.agent.learning.vector.VectorSearchResult;
import java.util.List;

public record LearningAgentResponse(
        String conversationId,
        String answer,
        String modelUsed,
        List<String> advisorTrace,
        int memoryMessages,
        List<VectorSearchResult> retrievedDocuments,
        List<McpToolExecution> toolExecutions,
        String disclaimer) {

    public LearningAgentResponse {
        advisorTrace = advisorTrace == null ? List.of() : List.copyOf(advisorTrace);
        retrievedDocuments = retrievedDocuments == null ? List.of() : List.copyOf(retrievedDocuments);
        toolExecutions = toolExecutions == null ? List.of() : List.copyOf(toolExecutions);
        disclaimer = "仅供学习研究，不构成投资建议";
    }
}
