package com.astock.agent.analysis;

import java.util.List;

public record DataQualityBreakdown(
        int freshness,
        int consistency,
        int completeness,
        int authority,
        int total,
        List<String> notes) {

    public DataQualityBreakdown {
        notes = notes == null ? List.of() : List.copyOf(notes);
        if (total != freshness + consistency + completeness + authority) {
            throw new IllegalArgumentException("Quality total must equal its components");
        }
    }
}
