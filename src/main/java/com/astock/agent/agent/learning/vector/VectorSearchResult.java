package com.astock.agent.agent.learning.vector;

import java.util.Map;

public record VectorSearchResult(
        String documentId,
        double score,
        String text,
        Map<String, Object> metadata) {

    public VectorSearchResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
