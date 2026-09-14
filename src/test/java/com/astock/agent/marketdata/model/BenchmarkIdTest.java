package com.astock.agent.marketdata.model;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class BenchmarkIdTest {
    @Test void mapsTencentCodesToHithinkCodes() {
        assertThat(BenchmarkId.CSI_300.hithinkCode()).isEqualTo("000300.SH");
        assertThat(BenchmarkId.SHANGHAI_COMPOSITE.hithinkCode()).isEqualTo("000001.SH");
        assertThat(BenchmarkId.SHENZHEN_COMPONENT.hithinkCode()).isEqualTo("399001.SZ");
        assertThat(BenchmarkId.CHI_NEXT.hithinkCode()).isEqualTo("399006.SZ");
    }

    @Test void keepsDisplayNamesForMarketStrip() {
        assertThat(BenchmarkId.SHANGHAI_COMPOSITE.displayName()).isEqualTo("上证指数");
        assertThat(BenchmarkId.SHENZHEN_COMPONENT.displayName()).isEqualTo("深证成指");
        assertThat(BenchmarkId.CHI_NEXT.displayName()).isEqualTo("创业板指");
    }
}
