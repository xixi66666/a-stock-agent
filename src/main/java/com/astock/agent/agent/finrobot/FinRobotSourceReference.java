package com.astock.agent.agent.finrobot;

import java.time.Instant;

/** FinRobot 报告的可追溯数据来源。 */
public record FinRobotSourceReference(
        String section,
        String provider,
        String sourceUrl,
        Instant fetchedAt) {
}
