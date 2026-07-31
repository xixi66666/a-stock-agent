package com.astock.agent.analysis.institutional;

public enum Direction {
    STRONGER("偏强"),
    NEUTRAL("中性"),
    WEAKER("偏弱"),
    INSUFFICIENT("证据不足");

    private final String label;

    Direction(String label) { this.label = label; }

    public String label() { return label; }
}
