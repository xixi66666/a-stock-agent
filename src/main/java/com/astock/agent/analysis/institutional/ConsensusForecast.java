package com.astock.agent.analysis.institutional;

import java.math.BigDecimal;
import java.util.Map;

public record ConsensusForecast(
        int coverage,
        BigDecimal currentYearEpsMedian,
        BigDecimal nextYearEpsMedian,
        BigDecimal currentYearDispersion,
        BigDecimal nextYearDispersion,
        String revisionTrend,
        Map<String, Integer> ratingDistribution) {

    public ConsensusForecast {
        ratingDistribution = ratingDistribution == null ? Map.of() : Map.copyOf(ratingDistribution);
    }
}
