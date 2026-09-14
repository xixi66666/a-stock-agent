package com.astock.agent.external;

import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.eastmoney.EastmoneyResearchClient;
import com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient;
import com.astock.agent.marketdata.provider.sina.SinaFinanceClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("external")
@SpringBootTest(properties = "spring.ai.model.chat=none")
class QuoteSourcesLiveIT {
    @Autowired EastmoneyResearchClient eastmoney;
    @Autowired SinaFinanceClient sina;
    @Autowired HithinkFinanceClient hithink;

    @Test void eastmoneyAndSinaReturnVerifiedCoreFieldsFor600519() {
        var security = SecurityId.parse("600519");
        var hithinkQuote = hithink.fetchQuote(security);
        assertThat(hithinkQuote.payload()).as("hithink: %s", hithinkQuote.issues()).isPresent();
        var reference = hithinkQuote.payload().orElseThrow().price();

        var eastmoneyQuote = eastmoney.fetchQuote(security);
        assertThat(eastmoneyQuote.payload()).as("eastmoney: %s", eastmoneyQuote.issues()).isPresent();
        assertThat(eastmoneyQuote.payload().orElseThrow().price()).isNotNull();
        assertThat(eastmoneyQuote.payload().orElseThrow().peTtm()).isNotNull();
        assertThat(eastmoneyQuote.payload().orElseThrow().pb()).isNotNull();
        assertThat(relativeDifference(eastmoneyQuote.payload().orElseThrow().price(), reference))
                .isLessThan(new BigDecimal("0.02"));

        var sinaQuote = sina.fetchQuote(security);
        assertThat(sinaQuote.payload()).as("sina: %s", sinaQuote.issues()).isPresent();
        assertThat(sinaQuote.payload().orElseThrow().price()).isNotNull();
        assertThat(relativeDifference(sinaQuote.payload().orElseThrow().price(), reference))
                .isLessThan(new BigDecimal("0.02"));
    }

    private static BigDecimal relativeDifference(BigDecimal value, BigDecimal reference) {
        return value.subtract(reference).abs().divide(reference, 4, RoundingMode.HALF_UP);
    }
}
