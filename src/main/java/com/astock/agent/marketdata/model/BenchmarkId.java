package com.astock.agent.marketdata.model;

public enum BenchmarkId {
    CSI_300("sh000300", "沪深300"),
    CSI_500("sh000905", "中证500"),
    CSI_1000("sh000852", "中证1000");

    private final String tencentCode;
    private final String displayName;

    BenchmarkId(String tencentCode, String displayName) {
        this.tencentCode = tencentCode;
        this.displayName = displayName;
    }

    public String tencentCode() {
        return tencentCode;
    }

    public String displayName() {
        return displayName;
    }
}
