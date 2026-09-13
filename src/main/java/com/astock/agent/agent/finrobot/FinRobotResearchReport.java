package com.astock.agent.agent.finrobot;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.FundFlowSummary;
import com.astock.agent.marketdata.model.IndustryValuationData;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 按 FinRobot equity research 角色组织的单证券研究报告。 */
public record FinRobotResearchReport(
        String ticker,
        String companyName,
        String tagline,
        String companyOverview,
        String investmentOverview,
        String valuationOverview,
        String risks,
        String competitorAnalysis,
        String majorTakeaways,
        String newsSummary,
        String dataQualitySummary,
        String technicalAndCapital,
        List<String> bullishEvidence,
        List<String> bearishEvidence,
        List<String> riskFactors,
        Map<String, String> scenarios,
        List<String> conflictsAndMissingData,
        List<FinRobotSourceReference> sourceReferences,
        DataSection<IndustryValuationData> industryValuation,
        DataSection<FundFlowSummary> fundFlowSummary,
        String modelName,
        Instant snapshotAt,
        Instant generatedAt,
        FinRobotGenerationMode generationMode,
        String pipelineVersion,
        String disclaimer) {

    public static final String REQUIRED_DISCLAIMER = "仅供学习研究，不构成投资建议";

    public FinRobotResearchReport {
        ticker = text(ticker);
        companyName = text(companyName);
        tagline = text(tagline);
        companyOverview = text(companyOverview);
        investmentOverview = text(investmentOverview);
        valuationOverview = text(valuationOverview);
        risks = text(risks);
        competitorAnalysis = text(competitorAnalysis);
        majorTakeaways = text(majorTakeaways);
        newsSummary = text(newsSummary);
        dataQualitySummary = text(dataQualitySummary);
        technicalAndCapital = text(technicalAndCapital);
        bullishEvidence = copy(bullishEvidence);
        bearishEvidence = copy(bearishEvidence);
        riskFactors = copy(riskFactors);
        scenarios = scenarios == null ? Map.of() : Map.copyOf(scenarios);
        conflictsAndMissingData = copy(conflictsAndMissingData);
        sourceReferences = copy(sourceReferences);
        industryValuation = industryValuation == null
                ? DataSection.unavailable("industry valuation not provided") : industryValuation;
        fundFlowSummary = fundFlowSummary == null
                ? DataSection.unavailable("fund flow summary not provided") : fundFlowSummary;
        modelName = text(modelName);
        pipelineVersion = pipelineVersion == null || pipelineVersion.isBlank()
                ? "finrobot-equity-v1" : pipelineVersion;
        disclaimer = REQUIRED_DISCLAIMER;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
