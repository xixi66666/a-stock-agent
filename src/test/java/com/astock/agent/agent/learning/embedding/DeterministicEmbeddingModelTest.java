package com.astock.agent.agent.learning.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class DeterministicEmbeddingModelTest {

    @Test
    void producesStableFiniteVectorsWithConfiguredDimension() {
        var model = new DeterministicEmbeddingModel(16);

        float[] first = model.embed("盈利增长");
        float[] second = model.embed("盈利增长");

        assertThat(first).hasSize(16);
        assertThat(first).containsExactly(second);
        for (float value : first) {
            assertTrue(Float.isFinite(value));
        }
    }

    @Test
    void mapsBlankTextToZeroVectorWithoutNetworkAccess() {
        assertThat(new DeterministicEmbeddingModel(8).embed(" "))
                .containsOnly(0.0f);
    }
}
