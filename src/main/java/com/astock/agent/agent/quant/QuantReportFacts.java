package com.astock.agent.agent.quant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 量化报告的事实包；只保存可复核指标、来源和限制。 */
public record QuantReportFacts(
        Map<String, MetricObservation> metrics,
        Map<String, BenchmarkComparison> benchmarkComparisons,
        List<String> observations,
        List<String> sourceIds,
        List<String> limitations) {

    public QuantReportFacts {
        metrics = metrics == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metrics));
        benchmarkComparisons = benchmarkComparisons == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(benchmarkComparisons));
        observations = observations == null ? List.of() : List.copyOf(observations);
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public MetricObservation metric(String id) {
        return metrics.getOrDefault(id, MetricObservation.unavailable(id, "", "", null,
                MetricAvailability.UNAVAILABLE, "未计算", sourceIds, "指标不存在"));
    }

    @Override
    public String toString() {
        return "QuantReportFacts[metrics=" + metrics + ", benchmarkComparisons="
                + benchmarkComparisons + ", observations=" + observations + ", sourceIds="
                + sourceIds + ", limitations=" + limitations + "]";
    }
}
