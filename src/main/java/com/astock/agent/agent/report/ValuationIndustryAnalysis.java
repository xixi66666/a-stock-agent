package com.astock.agent.agent.report;

import java.util.List;

public record ValuationIndustryAnalysis(String narrative, List<com.astock.agent.analysis.institutional.ReportEvidence> evidence) {
    public ValuationIndustryAnalysis {
        narrative = narrative == null ? "" : narrative;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
