package com.astock.agent.agent.quant;

import java.time.Instant;
import java.util.List;

/** 面向单一证券的无打分量化研究报告。 */
public record QuantResearchReport(
        ReportMeta reportMeta,
        PortfolioScope portfolioScope,
        String executiveSummary,
        String marketEnvironment,
        String securityPerformance,
        String factorObservations,
        String valuationAndFundamentals,
        String capitalAndEvents,
        String riskAndInvalidation,
        String outlook,
        String portfolioUnavailable,
        List<MetricObservation> metrics,
        List<BenchmarkComparison> benchmarkComparisons,
        List<String> sources,
        List<String> methods) {

    public QuantResearchReport {
        executiveSummary = text(executiveSummary);
        marketEnvironment = text(marketEnvironment);
        securityPerformance = text(securityPerformance);
        factorObservations = text(factorObservations);
        valuationAndFundamentals = text(valuationAndFundamentals);
        capitalAndEvents = text(capitalAndEvents);
        riskAndInvalidation = text(riskAndInvalidation);
        outlook = text(outlook);
        portfolioUnavailable = text(portfolioUnavailable);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        benchmarkComparisons = benchmarkComparisons == null ? List.of() : List.copyOf(benchmarkComparisons);
        sources = sources == null ? List.of() : List.copyOf(sources);
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    private static String text(String value) { return value == null ? "" : value; }

    public record ReportMeta(String reportType, String version, String securityCode, Instant generatedAt) {}
    public record PortfolioScope(String scope, List<String> unavailableReasons) {
        public PortfolioScope {
            unavailableReasons = unavailableReasons == null ? List.of() : List.copyOf(unavailableReasons);
        }
    }
}
