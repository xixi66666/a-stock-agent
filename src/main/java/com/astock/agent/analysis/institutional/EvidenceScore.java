package com.astock.agent.analysis.institutional;

import java.util.List;

public record EvidenceScore(
        String dimension,
        int rawScore,
        int weight,
        int weightedContribution,
        boolean usable,
        List<ReportEvidence> evidence) {

    public EvidenceScore {
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension is required");
        if (rawScore < -100 || rawScore > 100) throw new IllegalArgumentException("rawScore must be -100..100");
        if (weight < 0 || weight > 100) throw new IllegalArgumentException("weight must be 0..100");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
