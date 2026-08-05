package com.astock.agent.agent;

import com.astock.agent.analysis.ResearchAggregationService;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.technical.Timeframe;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 暴露给 Spring AI 的受限股票研究工具集合。
 *
 * <p>工具是 Agent 与业务系统之间的权限边界。每个工具只接受结构化、可限制的参数，
 * 并返回规范化数据；这里不能增加任意 URL、文件读写、Shell 或原始 HTTP 客户端。</p>
 */
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

    /**
     * 搜索少量常用股票。当前实现是离线学习用的有界目录，不把用户输入拼进 URL 或 SQL。
     */
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

    /**
     * 获取完整规范化研究快照，是多个 Agent 工具共享的事实入口。
     * {@link SecurityId#parse(String)} 会先验证证券身份格式，工具不允许任意 URL。
     */
    @Tool(description = "Get a normalized, source-cited stock research snapshot")
    public StockResearchSnapshot getResearchSnapshot(String code) {
        return research.apply(SecurityId.parse(code));
    }

    /** 时间周期先通过枚举校验，避免模型把任意字符串传入计算层。 */
    @Tool(description = "Get technical analysis for DAILY, WEEKLY, or MONTHLY timeframe")
    public Object getTechnicalAnalysis(String code, String timeframe) {
        Timeframe.valueOf(timeframe.toUpperCase(java.util.Locale.ROOT));
        return getResearchSnapshot(code).technical();
    }

    /** 返回带状态和来源的资金、基本面分区，而不是拼接后的自然语言。 */
    @Tool(description = "Get capital flows and normalized financial fundamentals")
    public Map<String, Object> getCapitalAndFundamentals(String code) {
        StockResearchSnapshot snapshot = getResearchSnapshot(code);
        return Map.of("capital", snapshot.capital(), "fundamentals", snapshot.fundamentals());
    }

    /** 获取公告、新闻和研报事件，并保留每个分区的来源元数据。 */
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
