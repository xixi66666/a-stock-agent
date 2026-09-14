package com.astock.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.analysis.MarketIndexService;
import com.astock.agent.marketdata.model.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketControllerTest {
    private final MarketIndexService service = new MarketIndexService(benchmarks -> List.of(
            new IndexQuoteSection(BenchmarkId.SHANGHAI_COMPOSITE, "上证指数",
                    DataSection.unverified(new IndexQuote(BenchmarkId.SHANGHAI_COMPOSITE, "上证指数",
                                    new BigDecimal("3123.45"), new BigDecimal("3113.20"),
                                    new BigDecimal("10.25"), new BigDecimal("0.33"),
                                    Instant.parse("2026-09-14T01:36:13Z")),
                            new Provenance("HiThink Finance", URI.create("https://example.test/snapshot"),
                                    Instant.parse("2026-09-14T01:36:00Z"), Instant.parse("2026-09-14T01:36:10Z"),
                                    false, null),
                            List.of("同花顺指数行情源时间是数据就绪时间，不是成交时间"))),
            new IndexQuoteSection(BenchmarkId.CHI_NEXT, "创业板指",
                    DataSection.unavailable("同花顺指数行情不可用；腾讯基准日线回退不可用"))));

    @Test void indicesExposeIdentityStatusAndPayload() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MarketController(service)).build();
        mvc.perform(get("/api/market/indices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indices[0].benchmark").value("SHANGHAI_COMPOSITE"))
                .andExpect(jsonPath("$.indices[0].displayName").value("上证指数"))
                .andExpect(jsonPath("$.indices[0].quote.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.indices[0].quote.payload.lastPoint").value(3123.45))
                .andExpect(jsonPath("$.indices[0].quote.payload.changePercent").value(0.33))
                .andExpect(jsonPath("$.indices[1].benchmark").value("CHI_NEXT"))
                .andExpect(jsonPath("$.indices[1].quote.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.indices[1].quote.payload").doesNotExist())
                .andExpect(jsonPath("$.indices[1].quote.issues[0]").isNotEmpty());
    }
}
