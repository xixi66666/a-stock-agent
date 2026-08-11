package com.astock.agent.agent.quant;

/** 指标是否可以基于当前证据计算。 */
public enum MetricAvailability {
    AVAILABLE,
    INSUFFICIENT_SAMPLE,
    INVALID_INPUT,
    UNAVAILABLE
}
