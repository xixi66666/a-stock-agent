package com.astock.agent.agent.report;

import java.util.List;

public record TechnicalAndFlowAnalysis(String narrative, List<com.astock.agent.analysis.institutional.ReportEvidence> evidence) {
    public TechnicalAndFlowAnalysis {
        narrative = narrative == null ? "" : narrative;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
