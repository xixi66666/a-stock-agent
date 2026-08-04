package com.astock.agent.analysis.institutional;

import com.astock.agent.marketdata.model.IndustryValuationData;
import java.util.List;

public record IndustryValuationCalculation(
        IndustryValuationData data,
        List<String> issues) {

    public IndustryValuationCalculation {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
