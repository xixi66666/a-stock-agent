package com.astock.agent.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.agent.uzi.UziResearchService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UziControllerTest {

    @Test
    void exposesUziRuntimeStatusWithoutSecrets() throws Exception {
        UziResearchService service = org.mockito.Mockito.mock(UziResearchService.class);
        when(service.status()).thenReturn(new UziResearchService.Status(
                false, false, false, "UZI_ROOT_NOT_FOUND", "tools/uzi/UZI-Skill", "python"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UziController(service)).build();

        mvc.perform(get("/api/uzi/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.installed").value(false))
                .andExpect(jsonPath("$.pythonAvailable").value(false))
                .andExpect(jsonPath("$.reason").value("UZI_ROOT_NOT_FOUND"))
                .andExpect(jsonPath("$.python").value("python"));
    }

    @Test
    void startsBoundedUziResearchTaskThroughThePublicEndpoint() throws Exception {
        UziResearchService service = org.mockito.Mockito.mock(UziResearchService.class);
        when(service.start("600519", "deep", null, null)).thenReturn(new UziResearchService.Task(
                "uzi-task-1", "600519", "RUNNING", "准备 UZI 深度分析", "deep", "deepseek-chat",
                Instant.parse("2026-09-13T00:00:00Z"), Instant.parse("2026-09-13T00:00:00Z"),
                null, null, List.of(), null, null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UziController(service)).build();

        mvc.perform(post("/api/uzi/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\",\"depth\":\"deep\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("uzi-task-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.depth").value("deep"));

        verify(service).start("600519", "deep", null, null);
    }

    @Test
    void passesSelectedModelIdToUziTask() throws Exception {
        UziResearchService service = org.mockito.Mockito.mock(UziResearchService.class);
        when(service.start("600519", "deep", "F", "mimo")).thenReturn(new UziResearchService.Task(
                "uzi-task-2", "600519", "RUNNING", "准备 UZI 深度分析", "deep", "mimo-v2",
                Instant.parse("2026-09-13T00:00:00Z"), Instant.parse("2026-09-13T00:00:00Z"),
                null, null, List.of(), null, null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UziController(service)).build();

        mvc.perform(post("/api/uzi/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\",\"depth\":\"deep\",\"school\":\"F\",\"modelId\":\"mimo\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.modelName").value("mimo-v2"));

        verify(service).start("600519", "deep", "F", "mimo");
    }
}
