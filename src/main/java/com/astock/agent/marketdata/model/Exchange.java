package com.astock.agent.marketdata.model;

public enum Exchange {
    SHANGHAI("sh", 1),
    SHENZHEN("sz", 0),
    BEIJING("bj", 0);

    private final String tencentPrefix;
    private final int eastmoneyMarket;

    Exchange(String tencentPrefix, int eastmoneyMarket) {
        this.tencentPrefix = tencentPrefix;
        this.eastmoneyMarket = eastmoneyMarket;
    }

    public String tencentPrefix() {
        return tencentPrefix;
    }

    public int eastmoneyMarket() {
        return eastmoneyMarket;
    }
}
