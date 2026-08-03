package com.astock.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.agent.report.GenerationMode;
import com.astock.agent.agent.report.ModelFailureStage;
import com.astock.agent.agent.report.NarrativeGenerator;
import com.astock.agent.agent.report.ReportNarrativeDraft;
import java.net.http.HttpTimeoutException;
import java.util.List;
import static org.mockito.Mockito.mock;
import org.springframework.ai.chat.client.ChatClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StockAnalysisAgentTest {

    @Test
    void synthesisPreservesMissingSectionsAndSourceTimesWithoutModel() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        AgentStatusService status = new AgentStatusService(new MockEnvironment());
        StockAnalysisAgent agent = new StockAnalysisAgent(
                (org.springframework.ai.chat.client.ChatClient) null, status, new StockAgentTools(id -> snapshot));

        AgentResearchReport report = agent.analyze(snapshot);

        assertThat(report.missingData()).contains("news", "technical", "fundamentals");
        assertThat(report.disclaimer()).isEqualTo("仅供学习研究，不构成投资建议");
        assertThat(report.sources()).allMatch(source -> source.fetchedAt() != null);
    }

    @Test
    void institutionalAnalysisFallsBackWithoutConfiguredModel() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        AgentStatusService status = new AgentStatusService(new MockEnvironment());
        StockAnalysisAgent agent = new StockAnalysisAgent(
                (org.springframework.ai.chat.client.ChatClient) null, status, new StockAgentTools(id -> snapshot));

        var report = agent.analyzeInstitutional(snapshot);

        assertThat(report.generationMode()).isEqualTo(GenerationMode.REPORT_UNAVAILABLE);
        assertThat(report.disclaimer()).isEqualTo("仅供学习研究，不构成投资建议");
    }

    @Test
    void timeoutReturnsDeterministicReportWithRealDiagnostic() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        AgentStatusService status = readyStatus();
        NarrativeGenerator generator = evidence -> {
            throw new HttpTimeoutException("model timed out after 30s");
        };
        StockAnalysisAgent agent = new StockAnalysisAgent(generator, status, new StockAgentTools(id -> snapshot));

        var report = agent.analyzeInstitutional(snapshot);

        assertThat(report.generationMode()).isEqualTo(GenerationMode.REPORT_UNAVAILABLE);
        assertThat(report.modelDiagnostic().errorCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(report.modelDiagnostic().message()).contains("30s");
    }

    @Test
    void validationFailureReturnsExactIssueCodes() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        NarrativeGenerator generator = evidence -> new ReportNarrativeDraft(
                "不存在的999亿元", "技术", "基本面", "估值", List.of(), List.of());
        StockAnalysisAgent agent = new StockAnalysisAgent(generator, readyStatus(), new StockAgentTools(id -> snapshot));

        var report = agent.analyzeInstitutional(snapshot);

        assertThat(report.modelDiagnostic().failureStage()).isEqualTo(ModelFailureStage.VALIDATION);
        assertThat(report.modelDiagnostic().validationIssues()).contains("UNSUPPORTED_NUMBER");
    }

    @Test
    void validationFailureFallsBackOnlyTheInvalidNarrativeField() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        NarrativeGenerator generator = evidence -> new ReportNarrativeDraft(
                "模型摘要", "建议买入", "模型基本面", "模型估值", List.of(), List.of());
        StockAnalysisAgent agent = new StockAnalysisAgent(generator, readyStatus(), new StockAgentTools(id -> snapshot));

        var report = agent.analyzeInstitutional(snapshot);

        assertThat(report.generationMode()).isEqualTo(GenerationMode.MODEL_ASSISTED_PARTIAL);
        assertThat(report.executiveSummary()).isEqualTo("模型摘要");
        assertThat(report.technicalAndFlow().narrative()).doesNotContain("建议买入");
        assertThat(report.fundamentals().narrative()).isEqualTo("模型基本面");
        assertThat(report.valuationAndIndustry().narrative()).isEqualTo("模型估值");
        assertThat(report.modelDiagnostic().validationIssues()).contains("TRADE_INSTRUCTION");
    }

    @Test
    void repairsOneValidationFailureBeforeUsingPartialFallback() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        NarrativeGenerator generator = new NarrativeGenerator() {
            private int repairs;

            @Override
            public ReportNarrativeDraft generate(com.astock.agent.agent.report.ReportEvidencePackage evidence) {
                return new ReportNarrativeDraft("模型摘要", "建议买入", "模型基本面", "模型估值", List.of(), List.of());
            }

            @Override
            public ReportNarrativeDraft repair(com.astock.agent.agent.report.ReportEvidencePackage evidence,
                    ReportNarrativeDraft draft, List<String> issues) {
                repairs++;
                return new ReportNarrativeDraft("修复后摘要", "修复后技术", "修复后基本面", "修复后估值", List.of(), List.of());
            }
        };
        StockAnalysisAgent agent = new StockAnalysisAgent(generator, readyStatus(), new StockAgentTools(id -> snapshot));

        var report = agent.analyzeInstitutional(snapshot);

        assertThat(report.generationMode()).isEqualTo(GenerationMode.MODEL_ASSISTED);
        assertThat(report.executiveSummary()).isEqualTo("修复后摘要");
        assertThat(report.technicalAndFlow().narrative()).isEqualTo("修复后技术");
        assertThat(report.modelDiagnostic()).isNull();
    }

    @Test
    void constructorAcceptsRealModelName() {
        var client = mock(ChatClient.class);
        var status = readyStatus();
        var agent = new StockAnalysisAgent(client, "deepseek-chat", status,
                new StockAgentTools(id -> StockResearchSnapshot.empty(SecurityId.parse("600519"))));
        assertThat(agent).isNotNull();
    }

    private static AgentStatusService readyStatus() {
        return new AgentStatusService(new MockEnvironment()
                .withProperty("spring.ai.openai.api-key", "test-value")
                .withProperty("spring.ai.model.chat", "openai"));
    }
}
