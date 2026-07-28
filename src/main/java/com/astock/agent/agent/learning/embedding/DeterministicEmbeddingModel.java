package com.astock.agent.agent.learning.embedding;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 仅用于离线学习和测试的确定性 Embedding，不代表生产级语义模型。
 */
public final class DeterministicEmbeddingModel implements EmbeddingModel {

    private final int dimension;

    public DeterministicEmbeddingModel(int dimension) {
        if (dimension < 8 || dimension > 1024) {
            throw new IllegalArgumentException("Embedding dimension must be between 8 and 1024");
        }
        this.dimension = dimension;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> result = new ArrayList<>();
        List<String> instructions = request.getInstructions();
        for (int index = 0; index < instructions.size(); index++) {
            result.add(new Embedding(embed(instructions.get(index)), index));
        }
        return new EmbeddingResponse(result);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document == null ? "" : document.getText());
    }

    @Override
    public int dimensions() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimension];
        if (text == null || text.isBlank()) {
            return vector;
        }
        text.codePoints().forEach(codePoint -> {
            int bucket = Math.floorMod(codePoint * 31 + 17, dimension);
            int sign = ((codePoint * 13) & 1) == 0 ? 1 : -1;
            vector[bucket] += sign;
        });
        normalize(vector);
        return vector;
    }

    private static void normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            return;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int index = 0; index < vector.length; index++) {
            vector[index] *= scale;
        }
    }
}
