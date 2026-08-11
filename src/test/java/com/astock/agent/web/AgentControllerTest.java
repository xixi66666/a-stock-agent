package com.astock.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.agent.AgentStatusService;
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
import com.astock.agent.analysis.institutional.Direction;
import com.astock.agent.analysis.institutional.EvidenceStatus;
import java.time.Instant;
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
}
