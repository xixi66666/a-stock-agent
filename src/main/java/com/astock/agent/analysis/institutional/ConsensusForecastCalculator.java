package com.astock.agent.analysis.institutional;

import com.astock.agent.marketdata.model.ResearchItem;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ConsensusForecastCalculator {

    public ConsensusForecast calculate(List<ResearchItem> reports) {
        Map<String, List<ResearchItem>> byInstitution = new HashMap<>();
        for (ResearchItem report : reports == null ? List.<ResearchItem>of() : reports) {
            if (report != null && report.organization() != null && !report.organization().isBlank()) {
                byInstitution.computeIfAbsent(report.organization().trim(), ignored -> new ArrayList<>()).add(report);
            }
        }
        List<ResearchItem> latest = byInstitution.values().stream()
                .map(items -> items.stream()
                        .max(Comparator.comparing(ResearchItem::publishedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                        .orElseThrow())
                .toList();
        Map<String, Integer> ratings = new HashMap<>();
        latest.stream()
                .map(ResearchItem::rating)
                .filter(value -> value != null && !value.isBlank())
                .forEach(value -> ratings.merge(value.trim(), 1, Integer::sum));
        List<BigDecimal> current = values(latest, true);
        List<BigDecimal> next = values(latest, false);
        return new ConsensusForecast(
                latest.size(), median(current), median(next), dispersion(current), dispersion(next),
                revisionTrend(byInstitution), ratings);
    }

    private static List<BigDecimal> values(List<ResearchItem> reports, boolean current) {
        return reports.stream()
                .map(report -> current ? report.currentYearEps() : report.nextYearEps())
                .filter(value -> value != null && value.abs().compareTo(BigDecimal.ZERO) > 0)
                .sorted()
                .toList();
    }

    private static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        int middle = values.size() / 2;
        if (values.size() % 2 == 1) {
            return values.get(middle);
        }
        return values.get(middle - 1).add(values.get(middle))
                .divide(BigDecimal.valueOf(2), MathContext.DECIMAL64);
    }

    private static BigDecimal dispersion(List<BigDecimal> values) {
        if (values.size() < 2) {
            return null;
        }
        BigDecimal median = median(values);
        BigDecimal p25 = percentile(values, 0.25);
        BigDecimal p75 = percentile(values, 0.75);
        if (median == null || median.signum() == 0) {
            return null;
        }
        return p75.subtract(p25).divide(median.abs(), MathContext.DECIMAL64)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentile(List<BigDecimal> values, double fraction) {
        double position = (values.size() - 1) * fraction;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return values.get(lower);
        }
        BigDecimal ratio = BigDecimal.valueOf(position - lower);
        return values.get(lower).add(values.get(upper).subtract(values.get(lower)).multiply(ratio));
    }

    private static String revisionTrend(Map<String, List<ResearchItem>> byInstitution) {
        int up = 0;
        int down = 0;
        for (List<ResearchItem> reports : byInstitution.values()) {
            List<ResearchItem> ordered = reports.stream()
                    .filter(item -> item.publishedAt() != null && item.currentYearEps() != null)
                    .sorted(Comparator.comparing(ResearchItem::publishedAt))
                    .toList();
            if (ordered.size() < 2) {
                continue;
            }
            BigDecimal previous = ordered.get(ordered.size() - 2).currentYearEps();
            BigDecimal current = ordered.getLast().currentYearEps();
            if (current.compareTo(previous) > 0) {
                up++;
            } else if (current.compareTo(previous) < 0) {
                down++;
            }
        }
        if (up > down) {
            return "UP";
        }
        if (down > up) {
            return "DOWN";
        }
        return "UNKNOWN";
    }
}
