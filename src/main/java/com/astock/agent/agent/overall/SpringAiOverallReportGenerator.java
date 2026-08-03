package com.astock.agent.agent.overall;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 面向 OpenAI 兼容接口的总体报告适配层。
 *
 * <p>这里仅负责提示词和结构化响应映射，不负责抓取数据、计算指标或做投资判断。
 * 这样可以保证 DeepSeek 与现有机构报告使用不同角色，同时共享同一份规范化快照。
 */
public final class SpringAiOverallReportGenerator implements OverallReportGenerator {

    /** 提示词版本会写入最终报告，修改约束时应同步递增。 */
    public static final String PROMPT_VERSION = "overall-v1";

    /** 给模型的系统约束：事实边界、质量状态、分析结构、安全边界和 JSON 格式。 */
    public static final String SYSTEM_PROMPT = """
            你是一个面向学习研究的专业 A 股研究报告分析师。下面的规范化快照是唯一事实边界：
            只能使用快照中明确出现的事实、计算指标、时间、来源和数据质量信息，不得联网、调用工具、
            猜测缺失值或补写快照之外的新闻、价格、财务数字。必须原样尊重每个分区的状态
            HEALTHY、DEGRADED、STALE、UNVERIFIED、UNAVAILABLE，并区分已确认事实、可复核的计算指标、
            数据冲突、缺失数据和基于证据的谨慎推断。UNAVAILABLE 分区只能说明不可用及其影响，不能编造替代值。

            请生成一个完整的 OverallReportDraft。overallConclusion 必须是基于证据的中性总结；
            dataQualitySummary 必须说明快照的新鲜度、一致性、完整性、权威性和核心数据限制；
            companyAndFundamentals、technicalAndCapital、valuationAndIndustry、eventsAndSentiment
            分别覆盖公司与基本面、技术与资金、估值与行业、事件与情绪，并在没有数据时明确写明不可用。
            bullishEvidence 只放可核验的正向证据，bearishEvidence 只放可核验的反向证据（正反证据必须同时保留），riskFactors
            只放风险与失效条件；scenarios 必须提供 stronger、neutral、weaker 三个条件式情景，不能写确定收益。
            conflictsAndMissingData 必须列出所有冲突、缺失和不可用分区及其对结论的限制。
            sourceReferences 只能引用快照中存在的 section、provider、sourceUrl 和 fetchedAt，不能伪造来源。

            不得输出买入、卖出、加仓、减仓、仓位、止盈、止损、目标价、保证收益、收益保证、稳赚等交易指令或个性化投资建议；
            严禁输出直接交易指令或个性化投资建议，包括但不限于：买入、卖出、加仓、减仓、仓位、止盈、止损、
            目标价、保证收益、收益保证、稳赚，以及任何确定性收益或目标价格。不要把风险提示改写成交易动作。
            只返回 OverallReportDraft 对应的 JSON，且必须是可解析的 JSON 对象，只包含 OverallReportDraft 的字段：
            overallConclusion、dataQualitySummary、companyAndFundamentals、technicalAndCapital、
            valuationAndIndustry、eventsAndSentiment、bullishEvidence、bearishEvidence、riskFactors、
            scenarios、conflictsAndMissingData、sourceReferences、disclaimer。不要 Markdown、不要代码围栏、
            不要额外字段或解释。disclaimer 必须逐字为：仅供学习研究，不构成投资建议。
            """;

    private static final String REPAIR_SYSTEM_PROMPT = SYSTEM_PROMPT + """

            这是一次且仅一次的校验修复。只修复校验问题列表指出的字段，保留其余事实、来源和限制，
            仍然只输出一个完整的 OverallReportDraft JSON，不得新增任何快照之外的信息。
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @FunctionalInterface
    interface ModelInvoker {
        OverallReportDraft invoke(String systemPrompt, String userPrompt) throws Exception;
    }

    private final ModelInvoker invoker;
    private final String modelName;

    /** 生产构造器：使用 Spring AI 的结构化 entity 映射，不暴露 fluent API 给业务层。 */
    public SpringAiOverallReportGenerator(ChatClient client, String modelName) {
        this((systemPrompt, userPrompt) -> client.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .entity(OverallReportDraft.class), modelName);
        Objects.requireNonNull(client, "client is required");
    }

    /** 包级测试构造器：避免测试 mock Spring AI fluent API。 */
    SpringAiOverallReportGenerator(ModelInvoker invoker, String modelName) {
        this.invoker = Objects.requireNonNull(invoker, "invoker is required");
        this.modelName = modelName == null || modelName.isBlank()
                ? "configured-overall-model" : modelName.trim();
    }

    @Override
    public OverallReportDraft generate(StockResearchSnapshot snapshot) throws Exception {
        Objects.requireNonNull(snapshot, "snapshot is required");
        return invoker.invoke(SYSTEM_PROMPT, generationPrompt(snapshot));
    }

    @Override
    public OverallReportDraft repair(StockResearchSnapshot snapshot,
            OverallReportDraft draft, List<String> issues) throws Exception {
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(draft, "draft is required");
        List<String> safeIssues = issues == null ? List.of() : List.copyOf(issues);
        String userPrompt = "以下是同一证券的完整规范化研究快照 JSON：\n"
                + MAPPER.writeValueAsString(snapshot)
                + "\n\n这是上一次草稿（上一轮返回的 OverallReportDraft JSON）：\n"
                + MAPPER.writeValueAsString(draft)
                + "\n\n确定性校验发现的问题列表：\n"
                + MAPPER.writeValueAsString(safeIssues)
                + "\n\n请只修复上述问题，返回完整且可解析的 OverallReportDraft JSON。";
        return invoker.invoke(REPAIR_SYSTEM_PROMPT, userPrompt);
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private static String generationPrompt(StockResearchSnapshot snapshot)
            throws JsonProcessingException {
        return "以下是证券 " + snapshot.security().code()
                + " 的完整规范化研究快照。请严格按照系统约束生成 OverallReportDraft JSON：\n"
                + MAPPER.writeValueAsString(snapshot);
    }
}
