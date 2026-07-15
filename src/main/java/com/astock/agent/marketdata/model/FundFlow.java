package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FundFlow(
        LocalDate date,
        BigDecimal mainNetYuan,
        BigDecimal smallNetYuan,
        BigDecimal mediumNetYuan,
        BigDecimal largeNetYuan,
        BigDecimal superLargeNetYuan,
        String source) {
}
