package com.astock.agent.analysis.institutional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Direction {
    STRONGER("偏强"),
    NEUTRAL("中性"),
    WEAKER("偏弱"),
    INSUFFICIENT("证据不足");

    private final String label;

    Direction(String label) { this.label = label; }

    @JsonValue
    public String label() { return label; }

    @JsonCreator
    public static Direction fromValue(String value) {
        for (Direction direction : values()) {
            if (direction.name().equalsIgnoreCase(value) || direction.label.equals(value)) return direction;
        }
        throw new IllegalArgumentException("Unknown direction: " + value);
    }
}
