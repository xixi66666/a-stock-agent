package com.astock.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(OutputCaptureExtension.class)
class RequestTracingTest {
    @Configuration
    @EnableWebMvc
    @ComponentScan("com.astock.agent.observability")
    static class Config {}

    @RestController
    static class ProbeController {
        @GetMapping("/api/probe")
        String probe() { return "ok"; }

        @GetMapping("/api/probe-failure")
        String fail() { throw new IllegalStateException("private-exception-message"); }
    }

    @Test
    void logsUnmappedAndFailedRequestsAndRestoresThreadContext(CapturedOutput output) throws Exception {
        var filter = new RequestTraceFilter();
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/missing");
        request.addHeader("X-Interaction-Id", "bad\nlog-injection");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        org.slf4j.MDC.put("requestId", "outer-request");
        try {
            filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(404));
            assertThat(org.slf4j.MDC.get("requestId")).isEqualTo("outer-request");
            assertThat(output.getOut()).contains("status=404", "interactionId=-").doesNotContain("log-injection");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(
                    new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/probe-failure"),
                    new org.springframework.mock.web.MockHttpServletResponse(),
                    (req, res) -> { throw new IllegalStateException("private-exception-message"); }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(output.getOut()).contains("status=500", "failureType=IllegalStateException")
                    .doesNotContain("private-exception-message");
            assertThat(org.slf4j.MDC.get("requestId")).isEqualTo("outer-request");
        } finally { org.slf4j.MDC.clear(); }
    }

    @Test
    void acceptsBoundedUiEventsAndRejectsLogInjection(CapturedOutput output) throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class, ProbeController.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(post("/api/observability/events").contentType("application/json")
                    .content("""
                        {"type":"click","pageId":"page-1","interactionId":"click-1",
                         "path":"/workbench.html","view":"technical","target":"button.view-tab","code":"600519"}
                        """))
                    .andExpect(status().isNoContent());
            assertThat(output.getOut()).contains("UI_EVENT", "view=technical", "interactionId=click-1");
            mvc.perform(post("/api/observability/events").contentType("application/json")
                    .content("""
                        {"type":"click","pageId":"page-1","interactionId":"click-1",
                         "path":"/workbench.html?token=secret","view":"technical","target":"injected\\nLOG","code":"600519"}
                        """))
                    .andExpect(status().isBadRequest());
            assertThat(output.getOut()).doesNotContain("token=secret", "injected");
        }
    }

    @Test
    void logsEveryRequestWithHandlerTimingAndCorrelationWithoutSecrets(CapturedOutput output) throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class, ProbeController.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBeansOfType(Filter.class).values().toArray(Filter[]::new)).build();
            var response = mvc.perform(get("/api/probe?apiKey=private-query")
                            .header("Authorization", "Bearer private-header")
                            .header("X-Interaction-Id", "click-123"))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getHeader("X-Request-Id")).isNotBlank();
            assertThat(output.getOut()).contains("HTTP_START", "HTTP_END", "path=/api/probe",
                    "status=200", "durationMs=", "ProbeController#probe", "interactionId=click-123")
                    .doesNotContain("private-query", "private-header");
            assertThat(org.slf4j.MDC.get("requestId")).isNull();
        }
    }
}
