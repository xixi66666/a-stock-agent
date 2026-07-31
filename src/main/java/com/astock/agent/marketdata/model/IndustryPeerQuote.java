package com.astock.agent.marketdata.model;

import java.math.BigDecimal;

public record IndustryPeerQuote(
        String code,
        String name,
        BigDecimal peDynamic,
        BigDecimal pb,
        BigDecimal totalMarketValueYuan) {
}
