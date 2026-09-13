package com.astock.agent.agent.finrobot;

import com.astock.agent.agent.overall.OverallResearchReport;
import com.astock.agent.agent.overall.OverallSourceReference;
import com.astock.agent.agent.report.InstitutionalResearchReport;
import com.astock.agent.agent.SourceCitation;
import com.astock.agent.analysis.StockResearchSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 将既有确定性分析和 FinRobot 草稿映射为统一的 equity research 契约。 */
final class FinRobotReportMapper {

    private FinRobotReportMapper() {
    }

    static FinRobotResearchReport fromOverall(
            OverallResearchReport report,
            StockResearchSnapshot snapshot,
            FinRobotGenerationMode mode) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(snapshot, "snapshot");
        String investmentOverview = investmentOverview(report.bullishEvidence(), report.bearishEvidence());
        return new FinRobotResearchReport(
                snapshot.security().code(), companyName(snapshot), report.overallConclusion(),
                report.companyAndFundamentals(), investmentOverview, report.valuationAndIndustry(),
                join(report.riskFactors()), report.valuationAndIndustry(), report.overallConclusion(),
                report.eventsAndSentiment(), report.dataQualitySummary(), report.technicalAndCapital(),
                report.bullishEvidence(), report.bearishEvidence(), report.riskFactors(), report.scenarios(),
                report.conflictsAndMissingData(), report.sourceReferences().stream()
                        .map(FinRobotReportMapper::source).toList(), report.industryValuation(),
                report.fundFlowSummary(), report.modelName(), report.snapshotAt(), report.generatedAt(),
                mode, "finrobot-equity-v1", FinRobotResearchReport.REQUIRED_DISCLAIMER);
    }

    static FinRobotResearchReport fromInstitutional(
            InstitutionalResearchReport report,
            StockResearchSnapshot snapshot,
            FinRobotGenerationMode mode) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(snapshot, "snapshot");
        List<String> bullish = report.coreDrivers().stream()
                .map(driver -> driver.conclusion())
                .filter(value -> value != null && !value.isBlank())
                .toList();
        String fundamental = report.fundamentals() == null ? "" : report.fundamentals().narrative();
        String technical = report.technicalAndFlow() == null ? "" : report.technicalAndFlow().narrative();
        String valuation = report.valuationAndIndustry() == null ? "" : report.valuationAndIndustry().narrative();
        String news = report.catalysts().stream().map(catalyst -> catalyst.title() + "：" + catalyst.interpretation())
                .collect(Collectors.joining("；"));
        String limitations = join(report.missingData());
        return new FinRobotResearchReport(
                snapshot.security().code(), companyName(snapshot), report.executiveSummary(), fundamental,
                join(bullish), valuation, join(report.risks()), valuation, join(bullish), news,
                "证据状态：" + report.evidenceStatus().name() + "；缺失数据：" + (limitations.isBlank() ? "无" : limitations),
                technical, bullish, report.conflicts(), report.risks(), Map.of(
                        "stronger", "现有偏强证据继续得到确认时，研究结论可维持偏强观察。",
                        "neutral", "证据保持分化时，研究结论维持中性观察。",
                        "weaker", "核心证据失效或风险证据增加时，研究结论转为偏弱观察。"),
                concat(report.conflicts(), report.missingData()), report.sources().stream()
                        .map(FinRobotReportMapper::source).toList(), snapshot.industryValuation(),
                snapshot.fundFlowSummary(), report.modelName(), report.snapshotAt(), report.generatedAt(),
                mode, "finrobot-equity-v1", FinRobotResearchReport.REQUIRED_DISCLAIMER);
    }

    private static String companyName(StockResearchSnapshot snapshot) {
        return snapshot.quote().payload().map(quote -> quote.name()).orElse("");
    }

    private static FinRobotSourceReference source(OverallSourceReference source) {
        return new FinRobotSourceReference(source.section(), source.provider(), source.sourceUrl(), source.fetchedAt());
    }

    private static FinRobotSourceReference source(SourceCitation source) {
        return new FinRobotSourceReference(source.section(), source.provider(), source.url(), source.fetchedAt());
    }

    private static String investmentOverview(List<String> bullish, List<String> bearish) {
        String positive = bullish == null || bullish.isEmpty() ? "暂无可核验的正向证据" : "支持证据：" + join(bullish);
        String negative = bearish == null || bearish.isEmpty() ? "暂无可核验的反向证据" : "反向证据：" + join(bearish);
        return positive + "；" + negative;
    }

    private static String join(List<String> values) {
        return values == null ? "" : values.stream().filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("；"));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        if (first != null) values.addAll(first);
        if (second != null) values.addAll(second);
        return List.copyOf(values);
    }
}
