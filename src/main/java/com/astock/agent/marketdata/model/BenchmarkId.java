package com.astock.agent.marketdata.model;

import java.util.Locale;

public enum BenchmarkId {
    CSI_300("sh000300", "沪深300"),
    CSI_500("sh000905", "中证500"),
    CSI_1000("sh000852", "中证1000"),
    SHANGHAI_COMPOSITE("sh000001", "上证指数"),
    SHENZHEN_COMPONENT("sz399001", "深证成指"),
    CHI_NEXT("sz399006", "创业板指");

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

    /** HiThink expects {@code 000300.SH} style codes; Tencent stores {@code sh000300}. */
    public String hithinkCode() {
        return tencentCode.substring(2) + "." + tencentCode.substring(0, 2).toUpperCase(Locale.ROOT);
    }
}
