package com.astock.agent.marketdata.model;

import java.util.List;
import java.util.Objects;

/** 按报告期升序对齐的三张财务报表历史。 */
public record FinancialStatementHistory(
        SecurityId security,
        List<FinancialPeriodStatement> periods) {

    public FinancialStatementHistory {
        security = Objects.requireNonNull(security, "security is required");
        periods = periods == null ? List.of() : List.copyOf(periods);
    }

    public int periodCount() {
        return periods.size();
    }
}
