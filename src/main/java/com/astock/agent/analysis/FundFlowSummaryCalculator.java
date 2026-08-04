package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.FundFlow;
import com.astock.agent.marketdata.model.FundFlowSummary;
import com.astock.agent.marketdata.model.FundFlowWindowSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;

public final class FundFlowSummaryCalculator {

    public FundFlowSummary calculate(List<FundFlow> values) {
        TreeMap<LocalDate, FundFlow> byDate = new TreeMap<>(Collections.reverseOrder());
        if (values != null) {
            values.stream()
                    .filter(value -> value != null && value.date() != null)
                    .forEach(value -> byDate.putIfAbsent(value.date(), value));
        }
        List<FundFlow> ordered = List.copyOf(byDate.values());
        return new FundFlowSummary(
                ordered.isEmpty() ? null : ordered.getFirst().date(),
                window(ordered, 1),
                window(ordered, 5),
                window(ordered, 20));
    }

    private static FundFlowWindowSummary window(List<FundFlow> ordered, int requestedDays) {
        List<FundFlow> values = ordered.stream().limit(requestedDays).toList();
        return new FundFlowWindowSummary(
                requestedDays,
                values.size(),
                sumComplete(values, FundFlow::mainNetYuan),
                sumComplete(values, FundFlow::superLargeNetYuan),
                sumComplete(values, FundFlow::largeNetYuan),
                sumComplete(values, FundFlow::mediumNetYuan),
                sumComplete(values, FundFlow::smallNetYuan));
    }

    private static BigDecimal sumComplete(List<FundFlow> values, Function<FundFlow, BigDecimal> getter) {
        if (values.isEmpty() || values.stream().anyMatch(value -> getter.apply(value) == null)) {
            return null;
        }
        return values.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
