package com.astock.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.analysis.candlestick.CandlestickAnalysisService;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.technical.BarSeriesFactory;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Clock;
import java.util.List;
import java.util.stream.IntStream;
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

    @Test
    void candlestickEndpointReturnsStructuredAnalysisWithKlineProvenance() throws Exception {
        Provenance source = source();
        StockResearchSnapshot withBars = snapshot(SecurityId.parse("600519"))
                .withBars(DataSection.healthy(bars(40), source));
        CandlestickAnalysisService candlestick = new CandlestickAnalysisService(
                new BarSeriesFactory(), Clock.fixed(Instant.parse("2026-09-04T08:00:00Z"), ZoneOffset.UTC));
        MockMvc candlestickMvc = MockMvcBuilders.standaloneSetup(
                        new StockController(ignored -> withBars, null, candlestick))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        candlestickMvc.perform(get("/api/stocks/600519/candlestick").queryParam("timeframe", "DAILY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.provenance.provider").value("fixture"))
                .andExpect(jsonPath("$.payload.timeframe").value("DAILY"))
                .andExpect(jsonPath("$.payload.methodology.ruleVersion").value("NISON-CANDLESTICK-1.1"))
                .andExpect(jsonPath("$.payload.methodology.analysisSequence[0]").value("前置趋势"));
    }

    private static StockResearchSnapshot snapshot(SecurityId security) {
        Provenance source = source();
        Quote quote = new Quote(security, "贵州茅台", bd(100), bd(99), bd(99), bd(101), bd(98), bd(1), bd(1),
                bd(1000), bd(100000), bd(1), bd(3), bd(1), bd(20), bd(20), bd(5), bd(1000000), bd(900000),
                bd(110), bd(90), Instant.now());
        return StockResearchSnapshot.empty(security).withQuote(DataSection.healthy(quote, source));
    }

    private static Provenance source() {
        return new Provenance("fixture", URI.create("https://example.com"), null, Instant.now(), false, null);
    }

    private static List<DailyBar> bars(int count) {
        return IntStream.range(0, count).mapToObj(index -> {
            double close = 100 + index * 0.2;
            return new DailyBar(LocalDate.of(2025, 1, 1).plusDays(index), bd(close - 0.2), bd(close + 0.8),
                    bd(close - 0.8), bd(close), bd(1_000_000 + index * 1_000), bd(close * 1_000_000));
        }).toList();
    }

    private static BigDecimal bd(double value) { return BigDecimal.valueOf(value); }
}
