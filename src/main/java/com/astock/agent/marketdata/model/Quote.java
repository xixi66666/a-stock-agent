package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Quote(
        SecurityId security,
        String name,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal changeAmount,
        BigDecimal changePercent,
        BigDecimal volumeShares,
        BigDecimal amountYuan,
        BigDecimal turnoverPercent,
        BigDecimal amplitudePercent,
        BigDecimal volumeRatio,
        BigDecimal peTtm,
        BigDecimal peStatic,
        BigDecimal pb,
        BigDecimal totalMarketValueYuan,
        BigDecimal circulatingMarketValueYuan,
        BigDecimal limitUp,
        BigDecimal limitDown,
        Instant quotedAt) {
}
