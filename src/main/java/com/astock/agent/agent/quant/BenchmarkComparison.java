package com.astock.agent.agent.quant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 个股与单一基准的可审计比较结果。 */
public record BenchmarkComparison(
        String benchmarkId,
        BigDecimal excessReturnPercent,
        BigDecimal beta,
        BigDecimal informationRatio,
        LocalDate asOf,
        MetricAvailability availability,
        String method,
        List<String> sourceIds,
        List<String> limitations) {

    public BenchmarkComparison {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
