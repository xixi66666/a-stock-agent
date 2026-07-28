package com.astock.agent.agent.learning.rag;

import com.astock.agent.agent.learning.vector.InMemoryResearchVectorStore;
import com.astock.agent.agent.learning.vector.VectorSearchResult;
import java.util.List;
import java.util.Objects;

public final class ResearchRetriever {

    private final InMemoryResearchVectorStore vectorStore;

    public ResearchRetriever(InMemoryResearchVectorStore vectorStore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
    }

    public RagContext retrieve(String question, String securityCode, int topK) {
        List<VectorSearchResult> documents = vectorStore.search(question, securityCode, topK);
        if (documents.isEmpty()) {
            return RagContext.missing("No matching research evidence");
        }
        String context = documents.stream()
                .map(document -> "[" + document.metadata().getOrDefault("section", "unknown") + "] "
                        + document.text() + " | source=" + document.metadata().getOrDefault("provider", "unknown")
                        + " | url=" + document.metadata().getOrDefault("sourceUrl", "unknown")
                        + " | score=" + String.format(java.util.Locale.ROOT, "%.3f", document.score()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return new RagContext(documents, context, "");
    }
}
