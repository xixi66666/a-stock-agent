package com.astock.agent.agent.quant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 单个可审计指标，不包含任何综合评分字段。 */
public record MetricObservation(
        String name,
        BigDecimal value,
        String unit,
        String window,
        LocalDate asOf,
        MetricAvailability availability,
        String method,
        List<String> sourceIds,
        List<String> limitations) {

    public MetricObservation {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(method, "method");
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        if (availability == MetricAvailability.AVAILABLE && value == null) {
            throw new IllegalArgumentException("available metric requires a value");
        }
        if (availability != MetricAvailability.AVAILABLE && value != null) {
            throw new IllegalArgumentException("unavailable metric cannot have a value");
        }
    }

    public static MetricObservation unavailable(String name, String unit, String window,
                                                LocalDate asOf, MetricAvailability availability,
                                                String method, List<String> sourceIds, String limitation) {
        return new MetricObservation(name, null, unit, window, asOf, availability, method,
                sourceIds, limitation == null ? List.of() : List.of(limitation));
    }
}
