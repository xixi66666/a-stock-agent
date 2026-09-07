package com.astock.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.agent.AgentStatusService;
import com.astock.agent.agent.financial.FinancialNarrative;
import com.astock.agent.agent.financial.FinancialReportAnalysis;
import com.astock.agent.agent.financial.FinancialReportService;
import com.astock.agent.agent.model.ModelNotAvailableException;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.agent.overall.OverallReportResponse;
import com.astock.agent.agent.overall.OverallReportService;
import com.astock.agent.agent.overall.OverallReportStatus;
import com.astock.agent.agent.overall.OverallResearchReport;
import com.astock.agent.agent.report.GenerationMode;
import com.astock.agent.agent.report.InstitutionalResearchReport;
import com.astock.agent.agent.quant.QuantResearchReportService;
import com.astock.agent.agent.quant.QuantResearchReport;
import com.astock.agent.analysis.FinancialDataUnavailableException;
import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialTrendResult;
import com.astock.agent.analysis.institutional.Direction;
import com.astock.agent.analysis.institutional.EvidenceStatus;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.Provenance;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.astock.agent.agent.StockAnalysisAgent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AgentControllerTest {

    @Test
    void statusReportsMissingConfigurationWithoutSecrets() throws Exception {
        AgentController controller = new AgentController(
                new AgentStatusService(new MockEnvironment()), null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/agent/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED_CONFIGURATION_MISSING"))
                .andExpect(jsonPath("$.details").value("Local model configuration is missing"));
    }

    @Test
    void analyzeReturnsInstitutionalReportContract() throws Exception {
        StockAnalysisAgent agent = mock(StockAnalysisAgent.class);
        InstitutionalResearchReport report = new InstitutionalResearchReport(
                Direction.STRONGER, "1-3个月", EvidenceStatus.PARTIAL, "summary", List.of(), null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), GenerationMode.DETERMINISTIC_FALLBACK,
                "rules-v1", "prompt-v1", null, Instant.now(), Instant.now(), "");
        when(agent.analyzeInstitutional("600519")).thenReturn(report);
        AgentController controller = new AgentController(new AgentStatusService(new MockEnvironment()), agent);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/agent/analyze").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"600519\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("STRONGER"))
                .andExpect(jsonPath("$.evidenceStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.generationMode").value("DETERMINISTIC_FALLBACK"))
                .andExpect(jsonPath("$.conflicts").isArray())
                .andExpect(jsonPath("$.invalidationConditions").isArray())
                .andExpect(jsonPath("$.disclaimer").value("仅供学习研究，不构成投资建议"));
    }

    @Test
    void quantReportUsesIndependentScoreFreeEndpoint() throws Exception {
        QuantResearchReportService service = mock(QuantResearchReportService.class);
        QuantResearchReport report = new QuantResearchReport(
                new QuantResearchReport.ReportMeta("QUANT_SINGLE_SECURITY", "v2", "600519", Instant.now()),
                new QuantResearchReport.PortfolioScope("SINGLE_SECURITY", List.of("组合级数据不可用")),
                "summary", "market", "performance", "factors", "valuation", "capital", "risk", "outlook",
                "UNAVAILABLE", List.of(), List.of(), List.of(), List.of());
        when(service.generate("600519")).thenReturn(report);
        AgentController controller = new AgentController(
                new AgentStatusService(new MockEnvironment()), null, null, service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/agent/quant-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportMeta.reportType").value("QUANT_SINGLE_SECURITY"))
                .andExpect(jsonPath("$.portfolioScope.scope").value("SINGLE_SECURITY"))
                .andExpect(jsonPath("$.portfolioUnavailable").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.score").doesNotExist());
        verify(service).generate("600519");
    }

    @Test
    void overallReportUsesIndependentEndpoint() throws Exception {
        OverallReportService overall = mock(OverallReportService.class);
        OverallResearchReport report = new OverallResearchReport(
                "summary", "quality", "fundamentals", "technical", "valuation", "events",
                List.of(), List.of(), List.of(), java.util.Map.of(), List.of(), List.of(),
                "deepseek-chat", Instant.now(), Instant.now(), "overall-v1", "ignored");
        when(overall.generate("600519", null)).thenReturn(new OverallReportResponse(
                OverallReportStatus.MODEL_ASSISTED, report, null, "DeepSeek overall report generated"));

        AgentController controller = new AgentController(
                new AgentStatusService(new MockEnvironment()), null, overall);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/agent/overall-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MODEL_ASSISTED"))
                .andExpect(jsonPath("$.report.modelName").value("deepseek-chat"));
        verify(overall).generate("600519", null);
    }

    @Test
    void listsOnlySafeOverallReportModelMetadata() throws Exception {
        OverallReportService overall = mock(OverallReportService.class);
        when(overall.availableModels()).thenReturn(List.of(
                new NamedChatClientRegistry.ModelReference("deepseek", "deepseek-chat", true),
                new NamedChatClientRegistry.ModelReference("mimo", "mimo-v2.5-pro", false)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(
                new AgentStatusService(new MockEnvironment()), null, overall)).build();

        mvc.perform(get("/api/agent/models").param("capability", "overall-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].id").value("deepseek"))
                .andExpect(jsonPath("$.models[0].modelName").value("deepseek-chat"))
                .andExpect(jsonPath("$.models[0].defaultModel").value(true))
                .andExpect(jsonPath("$.models[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$.models[0].baseUrl").doesNotExist());
    }

    @Test
    void overallReportPassesSelectedModelIdToService() throws Exception {
        OverallReportService overall = mock(OverallReportService.class);
        when(overall.generate("600519", "mimo")).thenReturn(new OverallReportResponse(
                OverallReportStatus.MODEL_NOT_CONFIGURED, null, null, "test"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(
                new AgentStatusService(new MockEnvironment()), null, overall)).build();

        mvc.perform(post("/api/agent/overall-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\",\"modelId\":\"mimo\"}"))
                .andExpect(status().isOk());

        verify(overall).generate("600519", "mimo");
    }

    @Test
    void unknownModelUsesStableProblemDetails() throws Exception {
        OverallReportService overall = mock(OverallReportService.class);
        when(overall.generate("600519", "unknown")).thenThrow(new ModelNotAvailableException());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(
                        new AgentStatusService(new MockEnvironment()), null, overall))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(post("/api/agent/overall-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\",\"modelId\":\"unknown\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_AVAILABLE"));
    }

    @Test
    void unsupportedModelCapabilityUsesStableProblemDetails() throws Exception {
        OverallReportService overall = mock(OverallReportService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(
                        new AgentStatusService(new MockEnvironment()), null, overall))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/api/agent/models").param("capability", "chat"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MODEL_CAPABILITY"));
    }

    @Test
    void invalidOverallReportCodeUsesProblemDetails() throws Exception {
        OverallReportService overall = mock(OverallReportService.class);
        AgentController controller = new AgentController(
                new AgentStatusService(new MockEnvironment()), null, overall);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(post("/api/agent/overall-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABC\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SECURITY_CODE"));
    }

    @Test
    void financialReportReturnsAnalysis() throws Exception {
        FinancialReportService service = mock(FinancialReportService.class);
        FinancialReportAnalysis analysis = new FinancialReportAnalysis(
                "600519", "2023-06-30 - 2026-03-31", 12,
                DataSection.healthy(new FinancialPeriodStatement(
                                LocalDate.parse("2026-03-31"),
                                BigDecimal.valueOf(57130000000L), BigDecimal.valueOf(4650000000L),
                                BigDecimal.valueOf(29530000000L), BigDecimal.valueOf(29520000000L),
                                BigDecimal.valueOf(12400000000L), BigDecimal.valueOf(345900000000L),
                                BigDecimal.valueOf(58900000000L), BigDecimal.valueOf(287400000000L),
                                BigDecimal.valueOf(55200000000L), BigDecimal.valueOf(1256000000L),
                                BigDecimal.valueOf(286800000000L)),
                        new Provenance("fixture", URI.create("https://example.com/financials"),
                                null, Instant.parse("2026-04-29T00:00:00Z"), false, null)),
                new FinancialQualityScore(6, "良", 9, List.of(), true),
                new FinancialTrendResult(List.of(), 12),
                new FinancialNarrative("F-Score 为 6 分，档位 良。", "信号正常。", "趋势正常。", "无风险。"),
                GenerationMode.DETERMINISTIC_FALLBACK, null, false,
                "2026-08-31T00:00:00Z", "financial-fscore-v1", "deterministic",
                FinancialReportAnalysis.REQUIRED_DISCLAIMER);
        when(service.generate("600519")).thenReturn(analysis);
        AgentController controller = new AgentController(
                new AgentStatusService(new MockEnvironment()), null, null, null, service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/agent/financial-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityCode").value("600519"))
                .andExpect(jsonPath("$.qualityScore.total").value(6))
                .andExpect(jsonPath("$.qualityScore.tier").value("良"))
                .andExpect(jsonPath("$.latestPeriod.status").value("HEALTHY"))
                .andExpect(jsonPath("$.latestPeriod.payload.reportPeriod[0]").value(2026))
                .andExpect(jsonPath("$.latestPeriod.provenance.provider").value("fixture"))
                .andExpect(jsonPath("$.generationMode").value("DETERMINISTIC_FALLBACK"));
        verify(service).generate("600519");
    }

    @Test
    void invalidFinancialReportCodeUsesProblemDetails() throws Exception {
        FinancialReportService service = mock(FinancialReportService.class);
        AgentController controller = new AgentController(
                new AgentStatusService(new MockEnvironment()), null, null, null, service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(post("/api/agent/financial-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SECURITY_CODE"));
    }

    @Test
    void unavailableFinancialDataUsesStableProblemDetails() throws Exception {
        FinancialReportService service = mock(FinancialReportService.class);
        when(service.generate("600519")).thenThrow(
                new FinancialDataUnavailableException("Sina statements failed"));
        AgentController controller = new AgentController(
                new AgentStatusService(new MockEnvironment()), null, null, null, service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(post("/api/agent/financial-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FINANCIAL_DATA_UNAVAILABLE"));
    }
}
