package com.astock.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.marketdata.provider.ProviderHealthRegistry;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SystemControllerTest {

    @Test
    void exposesProviderHealthWithoutCallingExternalServices() throws Exception {
        SystemController controller = new SystemController(
                new ProviderHealthRegistry(Duration.ofMinutes(30), Clock.systemUTC()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/system/data-sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.TENCENT.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.EASTMONEY.available").value(true));
    }
}
