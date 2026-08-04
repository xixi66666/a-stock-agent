package com.astock.agent.marketdata.model;

import java.math.BigDecimal;

public record FundFlowWindowSummary(
        int requestedDays,
        int sampleDays,
        BigDecimal mainNetYuan,
        BigDecimal superLargeNetYuan,
        BigDecimal largeNetYuan,
        BigDecimal mediumNetYuan,
        BigDecimal smallNetYuan) {
}
