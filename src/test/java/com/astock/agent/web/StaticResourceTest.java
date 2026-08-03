package com.astock.agent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.ai.model.chat=none")
@AutoConfigureMockMvc
class StaticResourceTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void servesActualResearchWorkbenchAtRoot() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        String html = mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("A 股智能研究台")
                .contains("data-view=\"technical\"");
    }

    @Test
    void servesResearchWorkbenchModuleScript() throws Exception {
        String script = mvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(script)
                .contains("stockApi.snapshot")
                .contains("data-symbol");
    }

    @Test
    void servesIndependentOverallReportApiModule() throws Exception {
        String script = mvc.perform(get("/js/api.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(script)
                .contains("overallReport(code)")
                .contains("/api/agent/overall-report");
    }

    @Test
    void servesIndependentOverallReportViewTemplate() throws Exception {
        String script = mvc.perform(get("/js/views.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(script)
                .contains("run-overall-report")
                .contains("overall-report-output")
                .contains("run-agent")
                .contains("agent-output");
    }
}
