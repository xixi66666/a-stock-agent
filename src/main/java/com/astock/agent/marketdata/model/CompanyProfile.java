package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CompanyProfile(
        SecurityId security,
        String name,
        String industry,
        List<Sector> sectors,
        BigDecimal totalShares,
        BigDecimal circulatingShares,
        LocalDate listedOn) {
}
