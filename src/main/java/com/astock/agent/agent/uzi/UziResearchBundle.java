package com.astock.agent.agent.uzi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** UZI Python 产物的版本化边界；原始维度保留，供 Java 适配和 FinRobot 消费。 */
public record UziResearchBundle(
        String schema,
        String ticker,
        String generatedAt,
        JsonNode rawData,
        JsonNode dimensions,
        JsonNode panel,
        JsonNode synthesis,
        Map<String, Object> structured,
        List<UziSourceReference> sources,
        List<String> dataGaps,
        String reportPath) {

    public UziResearchBundle {
        schema = schema == null || schema.isBlank() ? "uzi-bundle-v1" : schema;
        structured = structured == null ? Map.of() : Map.copyOf(structured);
        sources = sources == null ? List.of() : List.copyOf(sources);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
