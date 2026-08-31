package com.astock.agent.agent.financial;

import com.astock.agent.agent.report.GenerationMode;
import com.astock.agent.agent.report.ModelDiagnostic;
import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialTrendResult;

/** 财报分析最终响应:评分、趋势、叙事、生成模式与诊断。 */
public record FinancialReportAnalysis(
        String securityCode,
        String reportPeriodRange,
        int periodCount,
        FinancialQualityScore qualityScore,
        FinancialTrendResult trends,
        FinancialNarrative narrative,
        GenerationMode generationMode,
        ModelDiagnostic diagnostic,
        boolean financialIndustry,
        String generatedAt,
        String ruleVersion,
        String promptVersion,
        String disclaimer) {

    public static final String REQUIRED_DISCLAIMER = "仅供学习研究，不构成投资建议";
}
