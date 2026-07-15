package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record FundamentalData(
        LocalDate reportPeriod,
        Map<String, BigDecimal> metrics,
        Map<String, BigDecimal> yearOverYearPercent) {

    public FundamentalData {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        yearOverYearPercent = yearOverYearPercent == null ? Map.of() : Map.copyOf(yearOverYearPercent);
    }
}
