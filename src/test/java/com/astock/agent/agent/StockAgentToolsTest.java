package com.astock.agent.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StockAgentToolsTest {

    @Test
    void toolRejectsUnnormalizedSymbol() {
        StockAgentTools tools = new StockAgentTools(security -> {
            throw new AssertionError("service must not be called for invalid input");
        });

        assertThatThrownBy(() -> tools.getResearchSnapshot("贵州茅台<script>"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
