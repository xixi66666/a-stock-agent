package com.astock.agent.marketdata.provider.baidu;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.SecurityId;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BaiduKlineClientTest {

    @Test
    void mapsDynamicKeysAndPreservesYuanUnits() throws Exception {
        List<DailyBar> bars = BaiduKlineClient.parseDailyBars(
                fixture(), SecurityId.parse("600519"));

        assertThat(bars).hasSize(5);
        assertThat(bars.getLast().date()).isEqualTo(LocalDate.parse("2026-07-15"));
        assertThat(bars.getLast().close()).isEqualByComparingTo("1251.06");
        assertThat(bars.getLast().amountYuan()).isEqualByComparingTo("8922861367.00");
    }

    private static String fixture() throws Exception {
        try (var stream = BaiduKlineClientTest.class.getResourceAsStream("/fixtures/baidu/kline-600519.json")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
