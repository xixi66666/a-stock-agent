package com.astock.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.agent.report.GenerationMode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StockAnalysisAgentTest {

    @Test
    void synthesisPreservesMissingSectionsAndSourceTimesWithoutModel() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        AgentStatusService status = new AgentStatusService(new MockEnvironment());
        StockAnalysisAgent agent = new StockAnalysisAgent(null, status, new StockAgentTools(id -> snapshot));

        AgentResearchReport report = agent.analyze(snapshot);

        assertThat(report.missingData()).contains("news", "technical", "fundamentals");
        assertThat(report.disclaimer()).isEqualTo("仅供学习研究，不构成投资建议");
        assertThat(report.sources()).allMatch(source -> source.fetchedAt() != null);
    }

    @Test
    void institutionalAnalysisFallsBackWithoutConfiguredModel() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        AgentStatusService status = new AgentStatusService(new MockEnvironment());
        StockAnalysisAgent agent = new StockAnalysisAgent(null, status, new StockAgentTools(id -> snapshot));

        var report = agent.analyzeInstitutional(snapshot);

        assertThat(report.generationMode()).isEqualTo(GenerationMode.REPORT_UNAVAILABLE);
        assertThat(report.disclaimer()).isEqualTo("仅供学习研究，不构成投资建议");
    }
}
