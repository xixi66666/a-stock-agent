package com.astock.agent.agent.quant;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.SecurityId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuantReportComposerTest {
    @Test
    void fallbackStatesUnavailableDataExplicitly() {
        QuantReportFacts facts = new QuantReportFacts(Map.of(), Map.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of("个股日线不可用或未通过校验"));
        QuantResearchReport report = new QuantReportComposer().fallback(StockResearchSnapshot.empty(SecurityId.parse("600519")), facts);

        assertThat(report.executiveSummary()).contains("UNAVAILABLE");
        assertThat(report.portfolioScope().unavailableReasons()).isNotEmpty();
        assertThat(report.toString()).doesNotContain("score", "rank", "confidence", "aggregate");
    }
}
