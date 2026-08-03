package com.astock.agent.analysis.institutional;

import java.util.List;

/** 对综合方向影响最大的具体信号，供 API 和 UI 展示。 */
public record CoreDriver(
        String id,
        AnalysisModule module,
        Direction direction,
        String conclusion,
        String rationale,
        List<String> factIds,
        String invalidation) {

    public CoreDriver {
        if (id == null || id.isBlank() || module == null || direction == null
                || conclusion == null || conclusion.isBlank()
                || rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("Core driver identity and explanation are required");
        }
        factIds = factIds == null ? List.of() : List.copyOf(factIds);
        invalidation = invalidation == null ? "" : invalidation;
    }
}
