package com.astock.agent.agent.learning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LearningAgentPropertiesTest {

    @Test
    void clampsLearningBoundsToSafeOfflineValues() {
        LearningAgentProperties properties = new LearningAgentProperties(
                false, true, true, true, true, 1, 99, 2);

        assertThat(properties.maxHistoryMessages()).isEqualTo(2);
        assertThat(properties.topK()).isEqualTo(20);
        assertThat(properties.embeddingDimension()).isEqualTo(8);
    }

    @Test
    void preservesApprovedDefaults() {
        LearningAgentProperties properties = new LearningAgentProperties(
                false, true, true, true, true, 24, 4, 128);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.memoryEnabled()).isTrue();
        assertThat(properties.advisorEnabled()).isTrue();
        assertThat(properties.ragEnabled()).isTrue();
        assertThat(properties.mcpEnabled()).isTrue();
        assertThat(properties.maxHistoryMessages()).isEqualTo(24);
        assertThat(properties.topK()).isEqualTo(4);
        assertThat(properties.embeddingDimension()).isEqualTo(128);
    }
}
