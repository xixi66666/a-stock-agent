package com.astock.agent.agent.financial;

import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialTrendResult;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import java.util.List;
import java.util.Objects;

/** 给模型的唯一有界事实边界:评分、信号、趋势、来源与限制。 */
public record FinancialEvidencePackage(
        String securityCode,
        FinancialStatementHistory history,
        FinancialQualityScore qualityScore,
        FinancialTrendResult trends,
        boolean financialIndustry,
        int passCount,
        int failCount,
        int unverifiedCount,
        int signalCount,
        List<ScoreBand> scoreBands,
        com.astock.agent.marketdata.model.Provenance provenance,
        List<String> dataIssues) {

    public FinancialEvidencePackage(String securityCode, FinancialStatementHistory history,
            FinancialQualityScore qualityScore, FinancialTrendResult trends, boolean financialIndustry,
            int passCount, int failCount, int unverifiedCount, int signalCount, List<ScoreBand> scoreBands) {
        this(securityCode,history,qualityScore,trends,financialIndustry,passCount,failCount,unverifiedCount,
                signalCount,scoreBands,null,List.of());
    }

    public FinancialEvidencePackage withSource(com.astock.agent.marketdata.model.DataSection<?> source) {
        return new FinancialEvidencePackage(securityCode,history,qualityScore,trends,financialIndustry,
                passCount,failCount,unverifiedCount,signalCount,scoreBands,source.provenance().orElse(null),source.issues());
    }

    public FinancialEvidencePackage(String securityCode,
            FinancialStatementHistory history,
            FinancialQualityScore qualityScore,
            FinancialTrendResult trends,
            boolean financialIndustry,
            int passCount,
            int failCount,
            int unverifiedCount,
            int signalCount) {
        this(securityCode, history, qualityScore, trends, financialIndustry,
                passCount, failCount, unverifiedCount, signalCount, defaultScoreBands());
    }

    public FinancialEvidencePackage {
        dataIssues = dataIssues == null ? List.of() : List.copyOf(dataIssues);
        Objects.requireNonNull(history, "history is required");
        Objects.requireNonNull(qualityScore, "qualityScore is required");
        Objects.requireNonNull(trends, "trends is required");
        scoreBands = scoreBands == null || scoreBands.isEmpty()
                ? defaultScoreBands() : List.copyOf(scoreBands);
    }

    private static List<ScoreBand> defaultScoreBands() {
        return List.of(
                new ScoreBand(0, 2, "弱"),
                new ScoreBand(3, 5, "中"),
                new ScoreBand(6, 7, "良"),
                new ScoreBand(8, 9, "优"));
    }

    /** Prompt 中允许引用的确定性 F-Score 档位边界。 */
    public record ScoreBand(int minimum, int maximum, String tier) {
    }
}
