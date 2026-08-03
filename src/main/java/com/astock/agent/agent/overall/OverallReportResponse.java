package com.astock.agent.agent.overall;

import com.astock.agent.agent.report.ModelDiagnostic;

/** 总体报告及局部模型诊断响应。 */
public record OverallReportResponse(
        OverallReportStatus status,
        OverallResearchReport report,
        ModelDiagnostic diagnostic,
        String message) {
}
