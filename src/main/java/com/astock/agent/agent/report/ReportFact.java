package com.astock.agent.agent.report;

import java.time.Instant;

/** 报告中直接展示的结构化事实，不承担方向判断。 */
public record ReportFact(String id, String label, String value, String sourceId, Instant observedAt) {
    public ReportFact(String label, String value, String sourceId) {
        this(stableId(label), label, value, sourceId, null);
    }

    public ReportFact {
        if (id == null || id.isBlank() || label == null || label.isBlank()
                || value == null || value.isBlank()) {
            throw new IllegalArgumentException("Report fact id, label and value are required");
        }
        sourceId = sourceId == null ? "" : sourceId;
    }

    private static String stableId(String label) {
        String value = label == null ? "" : label.trim();
        return "fact-" + Integer.toUnsignedString(value.hashCode(), 36);
    }
}
