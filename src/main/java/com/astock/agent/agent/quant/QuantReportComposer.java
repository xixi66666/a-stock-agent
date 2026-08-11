package com.astock.agent.agent.quant;

import com.astock.agent.analysis.StockResearchSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 将确定性事实和经过校验的文字草稿组装为最终报告。 */
public final class QuantReportComposer {
    public QuantResearchReport fallback(StockResearchSnapshot snapshot, QuantReportFacts facts) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(facts, "facts");
        String unavailable = facts.limitations().isEmpty() ? "UNAVAILABLE：当前没有组合级数据。"
                : "UNAVAILABLE：" + String.join("；", facts.limitations());
        String summary = "本报告仅展示可复核的行情指标和证据化文字。"
                + metricLine(facts, "return-20", "20日收益") + " " + unavailable;
        String market = benchmarkLine(facts);
        String performance = metricLine(facts, "return-5", "5日收益") + " "
                + metricLine(facts, "return-20", "20日收益") + " "
                + metricLine(facts, "return-60", "60日收益");
        String factor = metricLine(facts, "annualized-volatility", "年化波动率") + " "
                + metricLine(facts, "downside-volatility", "下行波动率");
        String risk = metricLine(facts, "max-drawdown", "最大回撤") + " "
                + metricLine(facts, "var-95", "历史VaR 95%") + " "
                + metricLine(facts, "cvar-95", "历史CVaR 95%");
        String method = "指标由 Java 确定性规则计算；缺失、样本不足和来源限制不以 0 替代。";
        return report(snapshot, facts, summary, market, performance, factor,
                "UNAVAILABLE：当前快照未提供估值与基本面事实。",
                "UNAVAILABLE：当前快照未提供组合持仓、换手和资金事件事实。", risk,
                "前瞻展望仅在存在事实依据时描述；当前无额外确定性结论。", unavailable, List.of(method));
    }

    public QuantResearchReport compose(StockResearchSnapshot snapshot, QuantReportFacts facts,
                                       QuantNarrativeDraft draft, QuantNarrativeValidator.Validation validation,
                                       String modelName) {
        QuantResearchReport fallback = fallback(snapshot, facts);
        if (draft == null || validation == null || !validation.blockingIssues().isEmpty()) return fallback;
        return report(snapshot, facts,
                nonBlank(draft.executiveSummary(), fallback.executiveSummary()),
                nonBlank(draft.marketEnvironment(), fallback.marketEnvironment()),
                nonBlank(draft.securityPerformance(), fallback.securityPerformance()),
                nonBlank(draft.factorObservations(), fallback.factorObservations()),
                nonBlank(draft.valuationAndFundamentals(), fallback.valuationAndFundamentals()),
                nonBlank(draft.capitalAndEvents(), fallback.capitalAndEvents()),
                nonBlank(draft.riskAndOutlook(), fallback.riskAndInvalidation()),
                fallback.outlook(), fallback.portfolioUnavailable(),
                List.of("文字由" + (modelName == null || modelName.isBlank() ? "确定性回退" : modelName) + "组织；数字和来源来自事实包"));
    }

    public QuantResearchReport compose(StockResearchSnapshot snapshot, QuantReportFacts facts,
                                       QuantNarrativeDraft draft) {
        QuantNarrativeValidator.Validation validation = new QuantNarrativeValidator().validate(draft, facts);
        return compose(snapshot, facts, draft, validation, null);
    }

    private QuantResearchReport report(StockResearchSnapshot snapshot, QuantReportFacts facts,
                                       String summary, String market, String performance, String factors,
                                       String valuation, String capital, String risk, String outlook,
                                       String portfolioUnavailable, List<String> methods) {
        List<String> unavailable = new ArrayList<>(facts.limitations());
        unavailable.add("组合净值、持仓结构、换手率、对冲比例和 Brinson 归因不在单股快照范围内");
        return new QuantResearchReport(
                new QuantResearchReport.ReportMeta("QUANT_SINGLE_SECURITY", "v2", snapshot.security().code(), Instant.now()),
                new QuantResearchReport.PortfolioScope("SINGLE_SECURITY", unavailable), summary, market, performance,
                factors, valuation, capital, risk, outlook, portfolioUnavailable,
                List.copyOf(facts.metrics().values()), List.copyOf(facts.benchmarkComparisons().values()),
                facts.sourceIds(), methods);
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String metricLine(QuantReportFacts facts, String id, String label) {
        MetricObservation metric = facts.metric(id);
        if (metric.availability() != MetricAvailability.AVAILABLE) {
            return label + "=" + metric.availability().name();
        }
        return label + "=" + metric.value().stripTrailingZeros().toPlainString() + metric.unit();
    }

    private static String benchmarkLine(QuantReportFacts facts) {
        if (facts.benchmarkComparisons().isEmpty()) return "UNAVAILABLE：基准比较未提供。";
        return facts.benchmarkComparisons().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + (entry.getValue().availability() == MetricAvailability.AVAILABLE
                        ? entry.getValue().excessReturnPercent().stripTrailingZeros().toPlainString() + "%超额收益"
                        : entry.getValue().availability().name()))
                .collect(Collectors.joining("；"));
    }
}
