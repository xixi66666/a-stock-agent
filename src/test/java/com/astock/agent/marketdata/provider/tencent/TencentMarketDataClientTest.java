package com.astock.agent.marketdata.provider.tencent;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TencentMarketDataClientTest {

    private final TencentResponseParser parser = new TencentResponseParser();

    @Test
    void parsesTencentQuoteUnitsAndIdentity() throws Exception {
        Quote quote = parser.parseQuote(fixture("tencent/quote-600519.txt"), SecurityId.parse("600519"));

        assertThat(quote.name()).isEqualTo("贵州茅台");
        assertThat(quote.price()).isEqualByComparingTo("1251.06");
        assertThat(quote.previousClose()).isEqualByComparingTo("1214.88");
        assertThat(quote.volumeShares()).isEqualByComparingTo("7194400");
        assertThat(quote.amountYuan()).isEqualByComparingTo("8922860000");
        assertThat(quote.totalMarketValueYuan()).isEqualByComparingTo("1563927000000");
        assertThat(quote.pb()).isEqualByComparingTo("6.72");
    }

    @Test
    void parsesFrontAdjustedTencentBarsInAscendingOrder() throws Exception {
        List<DailyBar> bars = parser.parseDailyBars(
                fixture("tencent/kline-600519.json"), SecurityId.parse("600519"));

        assertThat(bars).hasSize(5);
        assertThat(bars.getFirst().date()).isEqualTo(LocalDate.parse("2026-07-09"));
        assertThat(bars.getLast().close()).isEqualByComparingTo(new BigDecimal("1251.060"));
        assertThat(bars.getLast().volumeShares()).isEqualByComparingTo("7194400");
    }

    private static String fixture(String name) throws Exception {
        try (var stream = TencentMarketDataClientTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
