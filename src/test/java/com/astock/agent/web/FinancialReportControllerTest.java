package com.astock.agent.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.agent.financial.FinancialReportService;
import com.astock.agent.agent.model.ModelNotAvailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FinancialReportControllerTest {

    private final FinancialReportService service = mock(FinancialReportService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FinancialReportController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void delegatesCodeAndModelId() throws Exception {
        mvc.perform(post("/api/agent/financial-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\",\"modelId\":\"mimo\"}"))
                .andExpect(status().isOk());

        verify(service).generate("600519", "mimo");
    }

    @Test
    void acceptsMissingModelId() throws Exception {
        mvc.perform(post("/api/agent/financial-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\"}"))
                .andExpect(status().isOk());

        verify(service).generate("600519", null);
    }

    @Test
    void invalidCodeUsesProblemDetails() throws Exception {
        when(service.generate("ABC", null)).thenThrow(new IllegalArgumentException("Invalid security code"));

        mvc.perform(post("/api/agent/financial-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABC\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SECURITY_CODE"));
    }

    @Test
    void unknownModelUsesProblemDetails() throws Exception {
        when(service.generate("600519", "ghost")).thenThrow(new ModelNotAvailableException());

        mvc.perform(post("/api/agent/financial-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\",\"modelId\":\"ghost\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_AVAILABLE"));
    }
}
