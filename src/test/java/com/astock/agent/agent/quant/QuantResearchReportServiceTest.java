package com.astock.agent.agent.quant;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.SecurityId;
import org.junit.jupiter.api.Test;

class QuantResearchReportServiceTest {
    @Test
    void returnsDeterministicReportWhenModelAndBenchmarksAreUnavailable() {
        StockAgentTools tools = new StockAgentTools(id -> StockResearchSnapshot.empty(id));
        QuantResearchReportService service = new QuantResearchReportService(
                tools,
                benchmark -> DataSection.unavailable("fixture unavailable"),
                new QuantFactsCalculator(),
                new QuantReportComposer(),
                new QuantNarrativeValidator(),
                new NamedChatClientRegistry(null, null));

        QuantResearchReport report = service.generate("600519");

        assertThat(report.reportMeta().reportType()).isEqualTo("QUANT_SINGLE_SECURITY");
        assertThat(report.portfolioScope().scope()).isEqualTo("SINGLE_SECURITY");
        assertThat(report.benchmarkComparisons()).hasSize(3)
                .allMatch(item -> item.availability() == MetricAvailability.UNAVAILABLE);
        assertThat(report.portfolioScope().unavailableReasons())
                .anyMatch(item -> item.contains("Brinson"));
    }
}
