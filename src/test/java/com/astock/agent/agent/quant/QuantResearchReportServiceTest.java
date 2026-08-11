package com.astock.agent.agent.quant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.SecurityId;
import org.springframework.ai.chat.client.ChatClient;
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
                new NamedChatClientRegistry(null, null));

        QuantResearchReport report = service.generate("600519");

        assertThat(report.reportMeta().reportType()).isEqualTo("QUANT_SINGLE_SECURITY");
        assertThat(report.portfolioScope().scope()).isEqualTo("SINGLE_SECURITY");
        assertThat(report.benchmarkComparisons()).hasSize(3)
                .allMatch(item -> item.availability() == MetricAvailability.UNAVAILABLE);
        assertThat(report.portfolioScope().unavailableReasons())
                .anyMatch(item -> item.contains("Brinson"));
    }

    @Test
    void keepsModelNarrativeWithoutQuantNarrativeValidation() {
        StockAgentTools tools = new StockAgentTools(id -> StockResearchSnapshot.empty(id));
        NamedChatClientRegistry registry = new NamedChatClientRegistry(
                java.util.Map.of("primary", new NamedChatClientRegistry.NamedModel(mock(ChatClient.class), "test-model")),
                java.util.Map.of("institutional-report", "primary"));
        QuantNarrativeDraft draft = new QuantNarrativeDraft(
                "模型评分与排名仅用于内部观察，暂不构成交易指令。",
                "模型市场环境文字。", "模型表现文字。", "模型因子文字。",
                "模型估值文字。", "模型资金文字。", "模型风险文字。");
        QuantResearchReportService service = new QuantResearchReportService(
                tools,
                benchmark -> DataSection.unavailable("fixture unavailable"),
                new QuantFactsCalculator(),
                new QuantReportComposer(),
                registry,
                ignored -> new QuantNarrativeGenerator() {
                    @Override
                    public QuantNarrativeDraft generate(QuantReportFacts facts) {
                        return draft;
                    }

                    @Override
                    public String modelName() {
                        return "test-model";
                    }
                });

        QuantResearchReport report = service.generate("600519");

        assertThat(report.executiveSummary()).contains("模型评分与排名");
        assertThat(report.methods()).anyMatch(item -> item.contains("test-model"));
    }
}
