package com.astock.agent.marketdata.provider.eastmoney;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.IndustryPeerQuote;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class EastmoneyIndustryValuationClientTest {

    private final EastmoneyResearchClient client = new EastmoneyResearchClient();

    @Test
    void parsesIndustryPeersWithProviderUnits() throws Exception {
        List<IndustryPeerQuote> peers = client.parseIndustryPeers(fixture("eastmoney/industry-peers-600519.json"));

        assertThat(peers).extracting(IndustryPeerQuote::code).contains("600519");
        assertThat(peers).filteredOn(peer -> peer.code().equals("600519"))
                .singleElement()
                .extracting(IndustryPeerQuote::totalMarketValueYuan)
                .isEqualTo(new java.math.BigDecimal("2100000000000"));
        assertThat(peers).allMatch(peer -> peer.totalMarketValueYuan() == null
                || peer.totalMarketValueYuan().signum() >= 0);
    }

    @Test
    void treatsMissingDataAsNullInsteadOfZero() throws Exception {
        List<IndustryPeerQuote> peers = client.parseIndustryPeers(fixture("eastmoney/industry-peers-600519.json"));

        IndustryPeerQuote invalid = peers.stream()
                .filter(peer -> peer.code().equals("002304"))
                .findFirst()
                .orElseThrow();
        assertThat(invalid.peDynamic()).isEqualByComparingTo("-5.0");
        assertThat(invalid.pb()).isNull();
    }

    private static String fixture(String name) throws Exception {
        try (var stream = EastmoneyIndustryValuationClientTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
