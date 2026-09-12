package com.astock.agent.external;

import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("external")
@SpringBootTest(properties = "spring.ai.model.chat=none")
class HithinkLiveDataIT {
    @Autowired HithinkFinanceClient client;
    @Autowired ObjectMapper json;

    @Test void fetchesNormalizedAnnualHistoryAndValuation() throws Exception {
        var security = SecurityId.parse("600519");
        var quote = client.fetchQuote(security);
        var bars = client.fetchDailyBars(security);
        var history = client.fetchStatementHistory(security);
        var valuation = client.fetchValuation(security);
        var dividends = client.fetchDividends(security);
        var dragonTiger = client.fetchDragonTiger(security);
        assertThat(quote.payload()).as("quote: %s", quote.issues()).isPresent();
        assertThat(quote.payload().orElseThrow().price()).isNotNull();
        assertThat(bars.payload()).as("daily bars: %s", bars.issues()).isPresent();
        assertThat(bars.payload().orElseThrow()).hasSizeGreaterThanOrEqualTo(260);
        assertThat(history.payload()).as("annual history: %s", history.issues()).isPresent();
        assertThat(valuation.payload()).as("valuation: %s", valuation.issues()).isPresent();
        assertThat(dividends.payload()).as("dividends: %s", dividends.issues()).isPresent();
        assertThat(dragonTiger.payload()).as("dragon tiger: %s", dragonTiger.issues()).isPresent();
        assertThat(history.payload().orElseThrow().periodCount()).isGreaterThanOrEqualTo(4);
        var latest = history.payload().orElseThrow().periods().getLast();
        assertThat(latest.operatingRevenue()).isNotNull();
        assertThat(latest.totalAssets()).isNotNull();
        assertThat(latest.operatingCashFlow()).isNotNull();
        assertThat(latest.equityAttributable()).isNull();
        assertThat(valuation.payload().orElseThrow().security()).isEqualTo(security);
        assertThat(valuation.payload().orElseThrow().peTtm()).isNotNull();
        var output = Path.of("target", "data-verification", "hithink-600519.json");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),
                Map.of("quote", quote, "bars", bars, "history", history, "valuation", valuation,
                        "dividends", dividends, "dragonTiger", dragonTiger));
    }
}
