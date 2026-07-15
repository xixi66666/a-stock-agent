package com.astock.agent.marketdata.provider;

public enum ProviderId {
    TENCENT("Tencent Finance"),
    BAIDU("Baidu Stock"),
    EASTMONEY("Eastmoney"),
    SINA("Sina Finance"),
    CNINFO("CNInfo"),
    SHANGHAI_EXCHANGE("Shanghai Stock Exchange"),
    SHENZHEN_EXCHANGE("Shenzhen Stock Exchange");

    private final String displayName;

    ProviderId(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
