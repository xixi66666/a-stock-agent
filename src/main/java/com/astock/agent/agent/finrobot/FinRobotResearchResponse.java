package com.astock.agent.agent.finrobot;

import com.astock.agent.agent.report.ModelDiagnostic;

/** FinRobot 投研接口的统一响应。 */
public record FinRobotResearchResponse(
        FinRobotResearchStatus status,
        FinRobotResearchReport report,
        ModelDiagnostic diagnostic,
        String message) {
}
