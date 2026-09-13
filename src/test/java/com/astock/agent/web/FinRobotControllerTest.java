package com.astock.agent.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.agent.AgentStatusService;
import com.astock.agent.agent.finrobot.FinRobotResearchResponse;
import com.astock.agent.agent.finrobot.FinRobotResearchService;
import com.astock.agent.agent.finrobot.FinRobotResearchStatus;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FinRobotControllerTest {

    @Test
    void exposesFinRobotStatusWithoutSecrets() throws Exception {
        MockMvc mvc = mvc(null);

        mvc.perform(get("/api/finrobot/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.status").value("DISABLED_CONFIGURATION_MISSING"));
    }

    @Test
    void researchUsesTheFinRobotEndpointAndSelectedModel() throws Exception {
        FinRobotResearchService service = org.mockito.Mockito.mock(FinRobotResearchService.class);
        when(service.generate("600519", "mimo")).thenReturn(new FinRobotResearchResponse(
                FinRobotResearchStatus.MODEL_ASSISTED, null, null, "FinRobot 投研报告已生成"));
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/finrobot/research")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\",\"modelId\":\"mimo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MODEL_ASSISTED"))
                .andExpect(jsonPath("$.message").value("FinRobot 投研报告已生成"));

        verify(service).generate("600519", "mimo");
    }

    @Test
    void listsOnlySafeFinRobotModelMetadata() throws Exception {
        FinRobotResearchService service = org.mockito.Mockito.mock(FinRobotResearchService.class);
        when(service.availableModels()).thenReturn(List.of(
                new NamedChatClientRegistry.ModelReference("mimo", "mimo-v2.5-pro", true)));
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/finrobot/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].id").value("mimo"))
                .andExpect(jsonPath("$.models[0].modelName").value("mimo-v2.5-pro"))
                .andExpect(jsonPath("$.models[0].apiKey").doesNotExist());
    }

    @Test
    void previousAgentAndQuantReportRoutesAreGone() throws Exception {
        MockMvc mvc = mvc(null);

        mvc.perform(post("/api/agent/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/agent/quant-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\"}"))
                .andExpect(status().isNotFound());
    }

    private MockMvc mvc(FinRobotResearchService service) {
        return MockMvcBuilders.standaloneSetup(new FinRobotController(
                        new AgentStatusService(new MockEnvironment()), service))
                .build();
    }
}
