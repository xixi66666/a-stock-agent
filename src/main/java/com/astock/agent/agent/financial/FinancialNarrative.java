package com.astock.agent.agent.financial;

/** 校验通过的最终叙事文本(不含生成模式,模式在 FinancialReportAnalysis 上)。 */
public record FinancialNarrative(
        String tierInterpretation,
        String signalCommentary,
        String trendCommentary,
        String riskNotes) {
}
