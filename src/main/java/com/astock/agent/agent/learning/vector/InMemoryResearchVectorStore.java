package com.astock.agent.agent.learning.vector;

import com.astock.agent.agent.learning.embedding.DeterministicEmbeddingModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.document.Document;

public final class InMemoryResearchVectorStore {

    private final DeterministicEmbeddingModel embeddingModel;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public InMemoryResearchVectorStore(DeterministicEmbeddingModel embeddingModel) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
    }

    public void add(List<Document> documents) {
        if (documents == null) {
            return;
        }
        for (Document document : documents) {
            if (document == null || document.getText() == null || document.getText().isBlank()) {
                continue;
            }
            entries.put(document.getId(), new Entry(
                    document.getId(), document.getText(), document.getMetadata(), embeddingModel.embed(document)));
        }
    }

    public List<VectorSearchResult> search(String query, String securityCode, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        float[] queryVector = embeddingModel.embed(query);
        return entries.values().stream()
                .filter(entry -> securityCode == null || securityCode.isBlank()
                        || securityCode.equals(String.valueOf(entry.metadata().get("securityCode"))))
                .map(entry -> result(entry, cosine(queryVector, entry.vector())))
                .sorted(Comparator.comparingDouble(VectorSearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    public void clear() {
        entries.clear();
    }

    private static VectorSearchResult result(Entry entry, double cosine) {
        return new VectorSearchResult(entry.id(), (cosine + 1.0) / 2.0, entry.text(), entry.metadata());
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private record Entry(String id, String text, Map<String, Object> metadata, float[] vector) {
        private Entry {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            vector = vector.clone();
        }
    }
}
