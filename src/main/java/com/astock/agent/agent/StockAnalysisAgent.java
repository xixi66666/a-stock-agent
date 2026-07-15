package com.astock.agent.agent;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.Provenance;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;

public final class StockAnalysisAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String SYSTEM_PROMPT = """
            你是一个用于学习研究的 A 股分析 Agent。只能依据工具返回的规范化数据和来源元数据回答。
            必须保留相互冲突的信号、不可用分区、数据时间和来源；不得编造实时价格、来源或缺失值。
            不提供直接买入卖出指令。结尾必须写：仅供学习研究，不构成投资建议。
            """;
    private final ChatClient chatClient;
    private final AgentStatusService statusService;
    private final StockAgentTools tools;

    public StockAnalysisAgent(ChatClient chatClient, AgentStatusService statusService, StockAgentTools tools) {
        this.chatClient = chatClient;
        this.statusService = statusService;
        this.tools = tools;
    }

    public AgentResearchReport analyze(String code) {
        return analyze(tools.getResearchSnapshot(code));
    }

    public AgentResearchReport analyze(StockResearchSnapshot snapshot) {
        AgentResearchReport fallback = evidenceOnlyReport(snapshot);
        if (chatClient == null || statusService.status() != AgentAvailability.READY) {
            return fallback;
        }
        try {
            String evidence = MAPPER.writeValueAsString(snapshot);
            AgentResearchReport report = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("请基于以下规范化快照生成结构化研究报告，并引用 sources：\n" + evidence)
                    .tools(tools)
                    .call()
                    .entity(AgentResearchReport.class);
            if (report == null) {
                throw new IllegalStateException("Model returned no structured report");
            }
            return report;
        } catch (Exception exception) {
            throw new AgentExecutionException("Structured agent analysis failed", exception);
        }
    }

    private static AgentResearchReport evidenceOnlyReport(StockResearchSnapshot snapshot) {
        Map<String, DataSection<?>> sections = sections(snapshot);
        List<String> missing = sections.entrySet().stream()
                .filter(entry -> entry.getValue().payload().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
        List<SourceCitation> citations = new ArrayList<>();
        sections.forEach((name, section) -> section.provenance().ifPresent(source ->
                citations.add(citation(name, source, section.status().name()))));
        List<String> technical = snapshot.technical().payload()
                .map(value -> value.cards().stream().limit(8)
                        .map(card -> card.name() + "：" + card.trigger()).toList())
                .orElse(List.of());
        String summary = snapshot.quote().payload()
                .map(quote -> quote.name() + "（" + quote.security().code() + "）最新数据价 " + quote.price())
                .orElse("核心行情不可用，无法生成事实摘要");
        return new AgentResearchReport(
                summary,
                "模型未启用时仅展示确定性指标证据，不做主观市场判断。",
                technical,
                List.of("资本与基本面分区状态已保留在快照中。"),
                snapshot.crossSourceConsistent() ? List.of() : List.of("跨来源价格或日期未通过一致性检查。"),
                List.of("请结合公告与数据状态核对事件风险。"),
                missing,
                citations,
                "这是基于已获取数据的学习型汇总；缺失分区不会被替代或推断。",
                "仅供学习研究，不构成投资建议");
    }

    private static Map<String, DataSection<?>> sections(StockResearchSnapshot snapshot) {
        Map<String, DataSection<?>> sections = new LinkedHashMap<>();
        sections.put("quote", snapshot.quote());
        sections.put("bars", snapshot.bars());
        sections.put("technical", snapshot.technical());
        sections.put("sectors", snapshot.sectors());
        sections.put("fundFlow", snapshot.fundFlow());
        sections.put("capital", snapshot.capital());
        sections.put("fundamentals", snapshot.fundamentals());
        sections.put("research", snapshot.research());
        sections.put("news", snapshot.news());
        sections.put("announcements", snapshot.announcements());
        return sections;
    }

    private static SourceCitation citation(String section, Provenance source, String status) {
        return new SourceCitation(
                section, source.provider(), source.sourceUrl().toString(), source.fetchedAt(), status);
    }
}
