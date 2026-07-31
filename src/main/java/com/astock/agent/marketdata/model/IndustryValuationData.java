package com.astock.agent.marketdata.model;

import java.math.BigDecimal;

public record IndustryValuationData(
        String industryCode,
        String industryName,
        int totalSamples,
        int validPeSamples,
        int excludedPeSamples,
        int validPbSamples,
        int excludedPbSamples,
        BigDecimal targetPe,
        BigDecimal peMedian,
        BigDecimal pePercentile,
        BigDecimal targetPb,
        BigDecimal pbMedian,
        BigDecimal pbPercentile) {
}
