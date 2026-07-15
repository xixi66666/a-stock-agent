package com.astock.agent.agent;

import java.util.List;

public record AgentResearchReport(
        String factualSummary,
        String trendAndRegime,
        List<String> technicalEvidence,
        List<String> capitalAndFundamentalEvidence,
        List<String> conflicts,
        List<String> eventRisks,
        List<String> missingData,
        List<SourceCitation> sources,
        String conclusion,
        String disclaimer) {

    public AgentResearchReport {
        technicalEvidence = copy(technicalEvidence);
        capitalAndFundamentalEvidence = copy(capitalAndFundamentalEvidence);
        conflicts = copy(conflicts);
        eventRisks = copy(eventRisks);
        missingData = copy(missingData);
        sources = copy(sources);
        disclaimer = "仅供学习研究，不构成投资建议";
    }

    private static <T> List<T> copy(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
