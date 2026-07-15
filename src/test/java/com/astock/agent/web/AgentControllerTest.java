package com.astock.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.agent.AgentStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
}
