package com.astock.agent.analysis.institutional;

import java.util.List;

/** 由结构化事实触发的一条确定性分析信号。 */
public record AnalysisSignal(
        String id,
        Direction direction,
        int impact,
        String conclusion,
        String rationale,
        List<String> factIds,
        String invalidation) {

    public AnalysisSignal {
        if (id == null || id.isBlank() || direction == null
                || conclusion == null || conclusion.isBlank()
                || rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("Signal identity, direction and explanation are required");
        }
        if (impact < 0 || impact > 100) {
            throw new IllegalArgumentException("Signal impact must be 0..100");
        }
        factIds = factIds == null ? List.of() : List.copyOf(factIds);
        invalidation = invalidation == null ? "" : invalidation;
    }
}
