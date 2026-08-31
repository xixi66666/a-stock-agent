package com.astock.agent.analysis.financial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 多期财务趋势:固定 8 个系列,每个系列含点位、同比、方向、加速度与波动率。 */
public record FinancialTrendResult(List<TrendSeries> series, int periodCount) {

    public FinancialTrendResult {
        series = series == null ? List.of() : List.copyOf(series);
    }

    public record TrendPoint(LocalDate period, BigDecimal value) {
    }

    public record TrendSeries(
            String name,
            String unit,
            String caliber,
            List<TrendPoint> points,
            List<BigDecimal> yoyGrowthPercent,
            String direction,
            BigDecimal acceleration,
            BigDecimal volatility) {
    }
}
