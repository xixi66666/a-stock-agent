package com.astock.agent.agent.financial;

/** 模型生成的受限叙事草稿;四段中文文本 + 固定免责声明。 */
public record FinancialNarrativeDraft(
        String tierInterpretation,
        String signalCommentary,
        String trendCommentary,
        String riskNotes,
        String disclaimer) {
}
