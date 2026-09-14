package com.astock.agent.marketdata.provider;

import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.eastmoney.EastmoneyResearchClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EastmoneyQuoteTest {
    private final EastmoneyResearchClient client = new EastmoneyResearchClient();

    @Test void parsesVerifiedUnitsAndIdentity() throws Exception {
        String body = Files.readString(Path.of("src/test/resources/fixtures/eastmoney/quote-600519.json"),
                StandardCharsets.UTF_8);
        Quote quote = client.parseQuote(body, SecurityId.parse("600519"));
        assertThat(quote.name()).isEqualTo("贵州茅台");
        assertThat(quote.price()).isEqualByComparingTo("1275.38");
        assertThat(quote.previousClose()).isEqualByComparingTo("1275.16");
        assertThat(quote.open()).isEqualByComparingTo("1277.27");
        assertThat(quote.high()).isEqualByComparingTo("1285.53");
        assertThat(quote.low()).isEqualByComparingTo("1270.36");
        assertThat(quote.changeAmount()).isEqualByComparingTo("0.22");
        assertThat(quote.changePercent()).isEqualByComparingTo("0.02");
        assertThat(quote.volumeShares()).isEqualByComparingTo("1333300");
        assertThat(quote.amountYuan()).isEqualByComparingTo("1702935275.0");
        assertThat(quote.turnoverPercent()).isEqualByComparingTo("0.11");
        assertThat(quote.amplitudePercent()).isEqualByComparingTo("1.19");
        assertThat(quote.volumeRatio()).isEqualByComparingTo("0.65");
        assertThat(quote.peTtm()).isEqualByComparingTo("19.58");
        assertThat(quote.peStatic()).isNull();
        assertThat(quote.pb()).isEqualByComparingTo("6.35");
        assertThat(quote.totalMarketValueYuan()).isEqualByComparingTo("1594329072283.3801");
        assertThat(quote.circulatingMarketValueYuan()).isEqualByComparingTo("1594329072283.3801");
        assertThat(quote.limitUp()).isEqualByComparingTo("1402.68");
        assertThat(quote.limitDown()).isEqualByComparingTo("1147.64");
        assertThat(quote.quotedAt()).isEqualTo(java.time.Instant.parse("2026-09-14T06:09:17Z"));
    }

    @Test void rejectsWrongIdentityAndBrokenOhlc() throws Exception {
        String body = Files.readString(Path.of("src/test/resources/fixtures/eastmoney/quote-600519.json"),
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client.parseQuote(body, SecurityId.parse("000001")))
                .isInstanceOf(IllegalArgumentException.class);
        String broken = body.replace("\"f44\":1285.53", "\"f44\":1260.0");
        assertThatThrownBy(() -> client.parseQuote(broken, SecurityId.parse("600519")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
