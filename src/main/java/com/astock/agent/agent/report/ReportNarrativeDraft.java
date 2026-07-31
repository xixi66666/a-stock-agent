package com.astock.agent.agent.report;

import java.util.List;

public record ReportNarrativeDraft(
        String executiveSummary,
        String technicalAndFlowNarrative,
        String fundamentalNarrative,
        String valuationAndIndustryNarrative,
        List<String> catalystNarratives,
        List<String> riskNarratives) {
    public ReportNarrativeDraft {
        catalystNarratives = catalystNarratives == null ? List.of() : List.copyOf(catalystNarratives);
        riskNarratives = riskNarratives == null ? List.of() : List.copyOf(riskNarratives);
    }
}
