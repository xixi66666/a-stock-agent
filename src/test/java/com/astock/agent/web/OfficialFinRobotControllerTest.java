package com.astock.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import com.astock.agent.agent.finrobot.OfficialFinRobotService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class OfficialFinRobotControllerTest {
    @Test void submitsBoundedTaskAndPreservesNotFoundStatus() throws Exception {
        var service = mock(OfficialFinRobotService.class);
        when(service.start("600519", "mimo")).thenReturn(new OfficialFinRobotService.Task(
                "task-id", "600519", "mimo", "RUNNING", "准备证据", 0, Instant.now(), Instant.now(), null, null));
        when(service.get("unknown")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));
        var mvc = MockMvcBuilders.standaloneSetup(new OfficialFinRobotController(service)).build();
        mvc.perform(post("/api/finrobot/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"600519\",\"modelId\":\"mimo\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.report").isEmpty()).andExpect(jsonPath("$.apiKey").doesNotExist());
        mvc.perform(get("/api/finrobot/tasks/unknown")).andExpect(status().isNotFound());
    }
}
