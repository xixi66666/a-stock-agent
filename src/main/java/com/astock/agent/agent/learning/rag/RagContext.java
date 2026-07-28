package com.astock.agent.agent.learning.rag;

import com.astock.agent.agent.learning.vector.VectorSearchResult;
import java.util.List;

public record RagContext(
        List<VectorSearchResult> documents,
        String formattedPromptContext,
        String missingReason) {

    public RagContext {
        documents = documents == null ? List.of() : List.copyOf(documents);
        formattedPromptContext = formattedPromptContext == null ? "" : formattedPromptContext;
        missingReason = missingReason == null ? "" : missingReason;
    }

    public static RagContext missing(String reason) {
        return new RagContext(List.of(), "", reason);
    }
}
