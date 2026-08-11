package com.astock.agent.agent.quant;

/** 大模型可选输出的纯文字草稿；不允许携带指标之外的结构化数字。 */
public record QuantNarrativeDraft(
        String executiveSummary,
        String marketEnvironment,
        String securityPerformance,
        String factorObservations,
        String valuationAndFundamentals,
        String capitalAndEvents,
        String riskAndOutlook) {

    public QuantNarrativeDraft {
        executiveSummary = normalize(executiveSummary);
        marketEnvironment = normalize(marketEnvironment);
        securityPerformance = normalize(securityPerformance);
        factorObservations = normalize(factorObservations);
        valuationAndFundamentals = normalize(valuationAndFundamentals);
        capitalAndEvents = normalize(capitalAndEvents);
        riskAndOutlook = normalize(riskAndOutlook);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
