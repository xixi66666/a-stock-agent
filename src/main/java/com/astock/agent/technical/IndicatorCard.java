package com.astock.agent.technical;

import com.astock.agent.marketdata.model.SectionStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record IndicatorCard(
        String id,
        String group,
        String name,
        Map<String, Integer> parameters,
        Double value,
        String unit,
        IndicatorState state,
        String trigger,
        List<Double> series,
        LocalDate calculatedAt,
        SectionStatus sectionStatus) {

    public IndicatorCard {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        series = series == null ? List.of() : List.copyOf(series);
        if (trigger == null || trigger.isBlank()) {
            throw new IllegalArgumentException("Indicator state requires an explicit trigger");
        }
    }
}
