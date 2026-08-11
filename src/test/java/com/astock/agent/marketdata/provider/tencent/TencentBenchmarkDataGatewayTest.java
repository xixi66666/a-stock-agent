package com.astock.agent.marketdata.provider.tencent;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.DailyBar;
import java.util.List;
import org.junit.jupiter.api.Test;

class TencentBenchmarkDataGatewayTest {

    private final TencentResponseParser parser = new TencentResponseParser();

    @Test
    void parsesDailyBarsByExplicitTencentIndexCode() {
        List<DailyBar> bars = parser.parseDailyBars("""
                {"data":{"sh000300":{"qfqday":[
                  ["2026-08-07","4000","4010","3990","4005","100000"],
                  ["2026-08-10","4005","4020","4000","4015","120000"]
                ]}}}
                """, "sh000300");

        assertThat(bars).hasSize(2);
        assertThat(bars.getFirst().date()).isBefore(bars.getLast().date());
        assertThat(bars.getLast().close()).isEqualByComparingTo("4020");
    }
}
