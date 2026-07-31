package com.astock.agent.analysis.institutional;

import java.time.Instant;

public record ReportEvidence(
        String id,
        String title,
        String interpretation,
        String section,
        String sourceId,
        Instant observedAt) {

    public ReportEvidence {
        if (id == null || id.isBlank() || title == null || title.isBlank()
                || interpretation == null || interpretation.isBlank()
                || section == null || section.isBlank()) {
            throw new IllegalArgumentException("Evidence id, title, interpretation and section are required");
        }
    }
}
