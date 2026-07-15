package com.astock.agent.marketdata.model;

import java.math.BigDecimal;

public record Sector(String name, String code, BigDecimal changePercent, String leader) {
}
