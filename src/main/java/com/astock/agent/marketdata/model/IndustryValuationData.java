package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.util.List;

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
        BigDecimal pbPercentile,
        List<IndustryPeerComparison> selectedPeers) {

    public IndustryValuationData {
        selectedPeers = selectedPeers == null ? List.of() : List.copyOf(selectedPeers);
    }

    public IndustryValuationData(
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
        this(industryCode, industryName, totalSamples, validPeSamples, excludedPeSamples,
                validPbSamples, excludedPbSamples, targetPe, peMedian, pePercentile,
                targetPb, pbMedian, pbPercentile, List.of());
    }
}
