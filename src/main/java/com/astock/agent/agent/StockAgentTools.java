package com.astock.agent.agent;

import com.astock.agent.analysis.ResearchAggregationService;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.technical.Timeframe;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.ai.tool.annotation.Tool;

public final class StockAgentTools {

    private static final Map<String, StockMatch> POPULAR = Map.of(
            "600519", new StockMatch("600519", "贵州茅台"),
            "000001", new StockMatch("000001", "平安银行"),
            "300750", new StockMatch("300750", "宁德时代"),
            "601318", new StockMatch("601318", "中国平安"),
            "000858", new StockMatch("000858", "五粮液"));
    private final Function<SecurityId, StockResearchSnapshot> research;

    public StockAgentTools(ResearchAggregationService service) {
        this(service::research);
    }

    public StockAgentTools(Function<SecurityId, StockResearchSnapshot> research) {
        this.research = research;
    }

    @Tool(description = "Search common A-share securities by exact six-digit code or Chinese name")
    public List<StockMatch> searchStock(String query) {
        if (query == null || query.isBlank() || query.length() > 20 || query.contains("<") || query.contains(">")) {
            throw new IllegalArgumentException("Search query is invalid");
        }
        String normalized = query.trim();
        return POPULAR.values().stream()
                .filter(item -> item.code().equals(normalized) || item.name().contains(normalized))
                .toList();
    }

    @Tool(description = "Get a normalized, source-cited stock research snapshot")
    public StockResearchSnapshot getResearchSnapshot(String code) {
        return research.apply(SecurityId.parse(code));
    }

    @Tool(description = "Get technical analysis for DAILY, WEEKLY, or MONTHLY timeframe")
    public Object getTechnicalAnalysis(String code, String timeframe) {
        Timeframe.valueOf(timeframe.toUpperCase(java.util.Locale.ROOT));
        return getResearchSnapshot(code).technical();
    }

    @Tool(description = "Get capital flows and normalized financial fundamentals")
    public Map<String, Object> getCapitalAndFundamentals(String code) {
        StockResearchSnapshot snapshot = getResearchSnapshot(code);
        return Map.of("capital", snapshot.capital(), "fundamentals", snapshot.fundamentals());
    }

    @Tool(description = "Get announcements, news, and research reports with provenance")
    public Map<String, Object> getEventsAndResearch(String code) {
        StockResearchSnapshot snapshot = getResearchSnapshot(code);
        return Map.of(
                "announcements", snapshot.announcements(),
                "news", snapshot.news(),
                "research", snapshot.research());
    }

    @Tool(description = "Explain one calculated indicator card without fetching arbitrary URLs")
    public Object explainIndicator(String code, String indicator, String timeframe) {
        Timeframe.valueOf(timeframe.toUpperCase(java.util.Locale.ROOT));
        return getResearchSnapshot(code).technical().payload()
                .map(snapshot -> snapshot.card(indicator.toUpperCase(java.util.Locale.ROOT)))
                .orElseThrow(() -> new IllegalStateException("Technical data is unavailable"));
    }

    public record StockMatch(String code, String name) {
    }
}
