package com.astock.agent.marketdata.model;

import java.util.Objects;

public record SecurityId(String code, Exchange exchange) {

    public SecurityId {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(exchange, "exchange");
    }

    public static SecurityId parse(String code) {
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalArgumentException("A-share code must contain exactly six digits");
        }

        Exchange exchange = switch (code.charAt(0)) {
            case '6' -> Exchange.SHANGHAI;
            case '0', '2', '3' -> Exchange.SHENZHEN;
            case '4', '8', '9' -> Exchange.BEIJING;
            default -> throw new IllegalArgumentException("Unsupported A-share code: " + code);
        };
        return new SecurityId(code, exchange);
    }

    public String tencentCode() {
        return exchange.tencentPrefix() + code;
    }

    public String eastmoneySecId() {
        return exchange.eastmoneyMarket() + "." + code;
    }
}
