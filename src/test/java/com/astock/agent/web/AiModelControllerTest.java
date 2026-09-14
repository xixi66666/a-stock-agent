package com.astock.agent.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.agent.model.ModelConnectivityRegistry;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiModelControllerTest {

    @Test
    void catalogMergesRolesAndConnectivityWithoutSecrets() throws Exception {
        var registry = new NamedChatClientRegistry(
                Map.of("deepseek", new NamedChatClientRegistry.NamedModel(
                        mock(ChatClient.class), "deepseek-chat")),
                Map.of("financial-report", "deepseek", "finrobot-research", "deepseek"));
        var states = new ModelConnectivityRegistry();
        states.recordOk("deepseek", 412L, Instant.parse("2026-09-14T02:00:00Z"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiModelController(registry, states)).build();

        mvc.perform(get("/api/ai/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].id").value("deepseek"))
                .andExpect(jsonPath("$.models[0].modelName").value("deepseek-chat"))
                .andExpect(jsonPath("$.models[0].defaultModel").value(true))
                .andExpect(jsonPath("$.models[0].roles",
                        containsInAnyOrder("financial-report", "finrobot-research")))
                .andExpect(jsonPath("$.models[0].connectivity.state").value("OK"))
                .andExpect(jsonPath("$.models[0].connectivity.latencyMs").value(412))
                .andExpect(jsonPath("$.models[0].connectivity.checkedAt").exists())
                .andExpect(content().string(not(containsString("apiKey"))))
                .andExpect(content().string(not(containsString("api-key"))));
    }

    @Test
    void unprobedModelsReportUnknownState() throws Exception {
        var registry = new NamedChatClientRegistry(
                Map.of("mimo", new NamedChatClientRegistry.NamedModel(mock(ChatClient.class), "mimo-v2")),
                Map.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AiModelController(registry, new ModelConnectivityRegistry())).build();

        mvc.perform(get("/api/ai/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].connectivity.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.models[0].connectivity.latencyMs").doesNotExist())
                .andExpect(jsonPath("$.models[0].roles").isEmpty());
    }
}
