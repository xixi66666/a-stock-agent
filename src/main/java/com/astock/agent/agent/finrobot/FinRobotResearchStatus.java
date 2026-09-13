package com.astock.agent.agent.finrobot;

/** FinRobot 单次投研流水线的结果状态。 */
public enum FinRobotResearchStatus {
    MODEL_ASSISTED,
    MODEL_NOT_CONFIGURED,
    MODEL_FAILED,
    DETERMINISTIC_FALLBACK
}
