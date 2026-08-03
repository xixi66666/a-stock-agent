package com.astock.agent.agent.report;

import com.astock.agent.agent.SourceCitation;
import com.astock.agent.analysis.institutional.Direction;
import com.astock.agent.analysis.institutional.EvidenceStatus;
import com.astock.agent.analysis.institutional.CoreDriver;
import java.time.Instant;
import java.util.List;

public record InstitutionalResearchReport(
        Direction direction,
        String horizon,
        EvidenceStatus evidenceStatus,
        String executiveSummary,
        List<CoreDriver> coreDrivers,
        TechnicalAndFlowAnalysis technicalAndFlow,
        FundamentalExpectationAnalysis fundamentals,
        ValuationIndustryAnalysis valuationAndIndustry,
        List<ReportEvent> catalysts,
        List<String> risks,
        List<String> conflicts,
        List<String> missingData,
        List<String> invalidationConditions,
        List<SourceCitation> sources,
        GenerationMode generationMode,
        String ruleVersion,
        String promptVersion,
        String modelName,
        Instant snapshotAt,
        Instant generatedAt,
        String disclaimer,
        ModelDiagnostic modelDiagnostic) {
    public InstitutionalResearchReport(Direction direction, String horizon, EvidenceStatus evidenceStatus,
            String executiveSummary, List<CoreDriver> coreDrivers, TechnicalAndFlowAnalysis technicalAndFlow,
            FundamentalExpectationAnalysis fundamentals, ValuationIndustryAnalysis valuationAndIndustry,
            List<ReportEvent> catalysts, List<String> risks, List<String> conflicts, List<String> missingData,
            List<String> invalidationConditions, List<SourceCitation> sources, GenerationMode generationMode,
            String ruleVersion, String promptVersion, String modelName, Instant snapshotAt, Instant generatedAt,
            String disclaimer) {
        this(direction, horizon, evidenceStatus, executiveSummary, coreDrivers, technicalAndFlow, fundamentals,
                valuationAndIndustry, catalysts, risks, conflicts, missingData, invalidationConditions, sources,
                generationMode, ruleVersion, promptVersion, modelName, snapshotAt, generatedAt, disclaimer, null);
    }

    public InstitutionalResearchReport {
        horizon = horizon == null ? "1-3个月" : horizon;
        executiveSummary = executiveSummary == null ? "" : executiveSummary;
        coreDrivers = coreDrivers == null ? List.of() : List.copyOf(coreDrivers);
        catalysts = catalysts == null ? List.of() : List.copyOf(catalysts);
        risks = risks == null ? List.of() : List.copyOf(risks);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        missingData = missingData == null ? List.of() : List.copyOf(missingData);
        invalidationConditions = invalidationConditions == null ? List.of() : List.copyOf(invalidationConditions);
        sources = sources == null ? List.of() : List.copyOf(sources);
        disclaimer = "仅供学习研究，不构成投资建议";
    }
}
