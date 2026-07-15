package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ResearchItem(
        String title,
        String organization,
        String rating,
        LocalDate publishedAt,
        String url,
        BigDecimal currentYearEps,
        BigDecimal nextYearEps) {
}
