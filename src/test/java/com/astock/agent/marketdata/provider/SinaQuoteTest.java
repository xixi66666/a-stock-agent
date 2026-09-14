package com.astock.agent.marketdata.provider;

import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.sina.SinaFinanceClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SinaQuoteTest {
    private final SinaFinanceClient client = new SinaFinanceClient();

    @Test void parsesQuoteAndComputesChangeFromPriceAndPreviousClose() throws Exception {
        String body = Files.readString(Path.of("src/test/resources/fixtures/sina/quote-600519.txt"),
                StandardCharsets.UTF_8);
        Quote quote = client.parseQuote(body, SecurityId.parse("600519"));
        assertThat(quote.name()).isEqualTo("贵州茅台");
        assertThat(quote.open()).isEqualByComparingTo("1277.270");
        assertThat(quote.previousClose()).isEqualByComparingTo("1275.160");
        assertThat(quote.price()).isEqualByComparingTo("1275.580");
        assertThat(quote.high()).isEqualByComparingTo("1285.530");
        assertThat(quote.low()).isEqualByComparingTo("1270.360");
        assertThat(quote.volumeShares()).isEqualByComparingTo("1334650");
        assertThat(quote.amountYuan()).isEqualByComparingTo("1704720964.000");
        assertThat(quote.changeAmount()).isEqualByComparingTo("0.42");
        assertThat(quote.changePercent()).isEqualByComparingTo("0.0329");
        assertThat(quote.turnoverPercent()).isNull();
        assertThat(quote.peTtm()).isNull();
        assertThat(quote.pb()).isNull();
        assertThat(quote.quotedAt()).isEqualTo(java.time.Instant.parse("2026-09-14T06:09:56Z"));
    }

    @Test void rejectsIdentityMismatch() throws Exception {
        String body = Files.readString(Path.of("src/test/resources/fixtures/sina/quote-600519.txt"),
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client.parseQuote(body, SecurityId.parse("000001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsBrokenOhlcRelationship() throws Exception {
        String body = Files.readString(Path.of("src/test/resources/fixtures/sina/quote-600519.txt"),
                StandardCharsets.UTF_8);
        String broken = body.replace(",1285.530,1270.360,", ",1260.000,1270.360,");
        assertThatThrownBy(() -> client.parseQuote(broken, SecurityId.parse("600519")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
