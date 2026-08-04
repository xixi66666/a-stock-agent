package com.astock.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StockControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        StockController controller = new StockController(security -> snapshot(security));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void snapshotReturns200ForPartialSuccess() throws Exception {
        mvc.perform(get("/api/stocks/600519/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quote.status").value("HEALTHY"))
                .andExpect(jsonPath("$.news.status").value("UNAVAILABLE"));
    }

    @Test
    void invalidCodeUsesProblemDetails() throws Exception {
        mvc.perform(get("/api/stocks/ABC/snapshot"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_SECURITY_CODE"));
    }

    @Test
    void sourcesExposeDerivedValuationAndFundFlowSections() throws Exception {
        mvc.perform(get("/api/stocks/600519/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.industryValuation.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.fundFlowSummary.status").value("UNAVAILABLE"));
    }

    private static StockResearchSnapshot snapshot(SecurityId security) {
        Provenance source = new Provenance("fixture", URI.create("https://example.com"), null, Instant.now(), false, null);
        Quote quote = new Quote(security, "贵州茅台", bd(100), bd(99), bd(99), bd(101), bd(98), bd(1), bd(1),
                bd(1000), bd(100000), bd(1), bd(3), bd(1), bd(20), bd(20), bd(5), bd(1000000), bd(900000),
                bd(110), bd(90), Instant.now());
        return StockResearchSnapshot.empty(security).withQuote(DataSection.healthy(quote, source));
    }

    private static BigDecimal bd(double value) { return BigDecimal.valueOf(value); }
}
