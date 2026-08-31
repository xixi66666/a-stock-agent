package com.astock.agent.analysis.financial;

import java.util.List;

/** F-Score 评分结果:0-9 分、档位与逐信号明细。 */
public record FinancialQualityScore(
        int total,
        String tier,
        int evaluatedSignals,
        List<SignalResult> signals,
        boolean sufficientData) {

    public FinancialQualityScore {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    public enum SignalStatus { PASS, FAIL, UNVERIFIED }

    public record SignalResult(int number, String name, SignalStatus status, String evidence) {
    }

    public static FinancialQualityScore insufficient() {
        return new FinancialQualityScore(0, "数据不足", 0, List.of(), false);
    }
}
