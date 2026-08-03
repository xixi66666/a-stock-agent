package com.astock.agent.agent.overall;

import java.util.List;
import java.util.Map;

/** 模型返回的总体报告草稿。 */
public record OverallReportDraft(
        String overallConclusion,
        String dataQualitySummary,
        String companyAndFundamentals,
        String technicalAndCapital,
        String valuationAndIndustry,
        String eventsAndSentiment,
        List<String> bullishEvidence,
        List<String> bearishEvidence,
        List<String> riskFactors,
        Map<String, String> scenarios,
        List<String> conflictsAndMissingData,
        List<OverallSourceReference> sourceReferences,
        String disclaimer) {

    public OverallReportDraft {
        bullishEvidence = safeList(bullishEvidence);
        bearishEvidence = safeList(bearishEvidence);
        riskFactors = safeList(riskFactors);
        scenarios = scenarios == null ? Map.of() : Map.copyOf(scenarios);
        conflictsAndMissingData = safeList(conflictsAndMissingData);
        sourceReferences = safeList(sourceReferences);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
