package com.astock.agent.marketdata.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.SectionStatus;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.cninfo.CninfoAnnouncementClient;
import com.astock.agent.marketdata.provider.eastmoney.EastmoneyResearchClient;
import com.astock.agent.marketdata.provider.sina.SinaFinanceClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ExtendedProviderContractTest {

    private final EastmoneyResearchClient eastmoney = new EastmoneyResearchClient();
    private final SinaFinanceClient sina = new SinaFinanceClient();
    private final CninfoAnnouncementClient cninfo = new CninfoAnnouncementClient();
    private final SecurityId security = SecurityId.parse("600519");

    @Test
    void parsesSectorsFundFlowReportsNewsAndCapitalEvents() throws Exception {
        assertThat(eastmoney.parseSectors(fixture("eastmoney/sectors-600519.json")))
                .extracting("name")
                .contains("白酒", "贵州板块");
        assertThat(eastmoney.parseFundFlow(fixture("eastmoney/fund-flow-600519.json")))
                .last()
                .extracting("mainNetYuan")
                .isEqualTo(new java.math.BigDecimal("238000000"));
        assertThat(eastmoney.parseReports(fixture("eastmoney/reports-600519.json")))
                .allMatch(report -> report.publishedAt() != null && report.url().startsWith("https://"));
        assertThat(eastmoney.parseNews(fixture("eastmoney/news-600519.json")))
                .allMatch(item -> item.publishedAt() != null && item.url().startsWith("https://"));

        var capital = eastmoney.parseCapitalData(fixture("eastmoney/capital-events-600519.json"));
        assertThat(capital.marginHistory()).hasSize(1);
        assertThat(capital.blockTrades()).hasSize(1);
        assertThat(capital.shareholderChanges()).hasSize(1);
        assertThat(capital.unlocks()).hasSize(1);
        assertThat(capital.dividends()).hasSize(1);
        assertThat(capital.dragonTigerRecords()).hasSize(1);
    }

    @Test
    void parsesSinaStatementsAndFundFlowFallback() throws Exception {
        var statements = sina.parseStatements(fixture("sina/statements-600519.json"));

        assertThat(statements.reportPeriod()).isEqualTo(java.time.LocalDate.parse("2026-03-31"));
        assertThat(statements.metrics()).containsKey("营业总收入");
        assertThat(statements.metrics()).doesNotContainKey("已赚保费");
        assertThat(sina.parseFundFlow(fixture("sina/fund-flow-600519.json")))
                .singleElement()
                .extracting("source")
                .isEqualTo("Sina Finance");
    }

    @Test
    void parsesCninfoAnnouncementsWithCanonicalPdfUrls() throws Exception {
        assertThat(cninfo.parseAnnouncements(fixture("cninfo/announcements-600519.json")))
                .allMatch(item -> item.url().startsWith("https://static.cninfo.com.cn/"));
    }

    @Test
    void distinguishesSuccessfulEmptyPayloadFromInvalidJson() {
        DataSection<?> empty = eastmoney.parseSectorsSection("{\"data\":{\"diff\":[]}}", security);
        DataSection<?> invalid = eastmoney.parseSectorsSection("not-json", security);

        assertThat(empty.status()).isEqualTo(SectionStatus.HEALTHY);
        assertThat(empty.payload()).hasValueSatisfying(value -> assertThat((java.util.List<?>) value).isEmpty());
        assertThat(invalid.status()).isEqualTo(SectionStatus.UNAVAILABLE);
        assertThat(invalid.issues()).anyMatch(issue -> issue.contains("parse"));
    }

    private static String fixture(String name) throws Exception {
        try (var stream = ExtendedProviderContractTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
