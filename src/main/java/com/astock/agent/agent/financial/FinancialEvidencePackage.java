package com.astock.agent.agent.financial;

import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialTrendResult;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
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
        int signalCount) {

    public FinancialEvidencePackage {
        Objects.requireNonNull(history, "history is required");
        Objects.requireNonNull(qualityScore, "qualityScore is required");
        Objects.requireNonNull(trends, "trends is required");
    }
}
