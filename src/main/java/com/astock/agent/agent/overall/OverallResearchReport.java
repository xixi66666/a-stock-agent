package com.astock.agent.agent.overall;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.FundFlowSummary;
import com.astock.agent.marketdata.model.IndustryValuationData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 经过服务端校验并附加生成元数据的总体研究报告。 */
public record OverallResearchReport(
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
        DataSection<IndustryValuationData> industryValuation,
        DataSection<FundFlowSummary> fundFlowSummary,
        String modelName,
        Instant snapshotAt,
        Instant generatedAt,
        String promptVersion,
        String disclaimer) {

    public static final String REQUIRED_DISCLAIMER = "仅供学习研究，不构成投资建议";

    public OverallResearchReport {
        bullishEvidence = safeList(bullishEvidence);
        bearishEvidence = safeList(bearishEvidence);
        riskFactors = safeList(riskFactors);
        scenarios = scenarios == null ? Map.of() : Map.copyOf(scenarios);
        conflictsAndMissingData = safeList(conflictsAndMissingData);
        sourceReferences = safeList(sourceReferences);
        industryValuation = industryValuation == null
                ? DataSection.unavailable("industry valuation not provided")
                : industryValuation;
        fundFlowSummary = fundFlowSummary == null
                ? DataSection.unavailable("fund flow summary not provided")
                : fundFlowSummary;
    }

    public OverallResearchReport(
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
            String modelName,
            Instant snapshotAt,
            Instant generatedAt,
            String promptVersion,
            String disclaimer) {
        this(overallConclusion, dataQualitySummary, companyAndFundamentals, technicalAndCapital,
                valuationAndIndustry, eventsAndSentiment, bullishEvidence, bearishEvidence,
                riskFactors, scenarios, conflictsAndMissingData, sourceReferences,
                DataSection.unavailable("industry valuation not provided"),
                DataSection.unavailable("fund flow summary not provided"),
                modelName, snapshotAt, generatedAt, promptVersion, disclaimer);
    }

    public static OverallResearchReport from(OverallReportDraft draft, String modelName,
            Instant snapshotAt, Instant generatedAt, String promptVersion) {
        Objects.requireNonNull(draft, "draft");
        return new OverallResearchReport(
                draft.overallConclusion(), draft.dataQualitySummary(),
                draft.companyAndFundamentals(), draft.technicalAndCapital(),
                draft.valuationAndIndustry(), draft.eventsAndSentiment(),
                draft.bullishEvidence(), draft.bearishEvidence(), draft.riskFactors(),
                draft.scenarios(), draft.conflictsAndMissingData(), draft.sourceReferences(),
                modelName, snapshotAt, generatedAt, promptVersion, draft.disclaimer());
    }

    public static OverallResearchReport fromSnapshot(
            OverallReportDraft draft,
            String modelName,
            StockResearchSnapshot snapshot,
            Instant generatedAt,
            String promptVersion) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(snapshot, "snapshot");
        return new OverallResearchReport(
                draft.overallConclusion(), draft.dataQualitySummary(),
                draft.companyAndFundamentals(), draft.technicalAndCapital(),
                draft.valuationAndIndustry(), draft.eventsAndSentiment(),
                draft.bullishEvidence(), draft.bearishEvidence(), draft.riskFactors(),
                draft.scenarios(), draft.conflictsAndMissingData(), draft.sourceReferences(),
                snapshot.industryValuation(), snapshot.fundFlowSummary(),
                modelName, snapshot.fetchedAt(), generatedAt, promptVersion, draft.disclaimer());
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
