package com.astock.agent.marketdata.model;

import java.time.LocalDate;

public record FundFlowSummary(
        LocalDate latestDate,
        FundFlowWindowSummary latestDay,
        FundFlowWindowSummary fiveDay,
        FundFlowWindowSummary twentyDay) {
}
