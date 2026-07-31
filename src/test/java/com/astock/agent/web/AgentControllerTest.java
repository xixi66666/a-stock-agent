package com.astock.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.agent.AgentStatusService;
import com.astock.agent.agent.report.GenerationMode;
import com.astock.agent.agent.report.InstitutionalResearchReport;
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
}
