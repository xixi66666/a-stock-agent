package com.astock.agent.agent.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 面向 OpenAI 兼容接口的财务叙事适配器(DeepSeek)。
 *
 * <p>只负责 Prompt 与结构化实体映射;事实全部来自 {@link FinancialEvidencePackage},
 * 结果必须经过 {@link FinancialReportValidator} 校验。</p>
 */
public final class SpringAiFinancialNarrativeGenerator implements FinancialReportGenerator {

    public static final String PROMPT_VERSION = "financial-v3";

    public static final String SYSTEM_PROMPT = """
            你是一名严谨的 A 股财务分析助理。下面的 JSON 是唯一事实边界：财务质量评分(F-Score 0-9)、
            9 个信号明细、多期趋势序列与统计，全部由确定性规则计算。只能使用该 JSON 中出现的数字、
            时期、状态与证据，不得联网、调用工具、猜测或补写任何额外数字与结论。
            UNVERIFIED 表示字段或历史期数不足，只能如实说明“无法评估”，不能当作失败或通过。

            请生成 FinancialNarrativeDraft JSON，只包含以下字段：
            写作要求：先说结论，再说原因；使用普通投资者能理解的中文。不要堆砌指标名，不要逐条复述
            9 个信号，不要使用“方向为”“条件成立”等机器式表达。TTM 首次出现时解释为“最近四个季度”。

            tierInterpretation：以“总体判断：”开头，用一句话说明财务质量档位和它代表的含义；必须逐字包含
                档位文字 弱/中/良/优 或 数据不足，并说明 F-Score 综合观察盈利、现金流、杠杆和经营效率。
            signalCommentary：把 PASS 归为“表现较好”，FAIL 归为“需要关注”，UNVERIFIED 归为“暂时无法判断”；
                只点出最重要的项目及其含义，详细证据交给页面信号明细，不逐条复述。
            trendCommentary：优先解释营业收入、归母净利润、经营现金流和资产负债率的最新同比变化，说明这些
                变化代表增长、现金兑现或偿债压力的什么状态；只引用 JSON 中 trends 的数值与方向。
            riskNotes：用白话说明结论的限制和后续应重点观察的项目，包括 UNVERIFIED、金融行业限制与累计口径。
            disclaimer：必须逐字为：仅供学习研究，不构成投资建议

            不得输出买入、卖出、加仓、减仓、仓位、止盈、止损、目标价、保证收益、收益保证、稳赚等
            交易指令或个性化投资建议；限制说明统一写成“仅作研究，不提供操作建议”。
            不要输出 JSON 之外的数字、百分比、日期或年份。不要 Markdown、不要代码围栏、不要额外字段。
            """;

    private static final String REPAIR_SYSTEM_PROMPT = SYSTEM_PROMPT + """

            这是一次且仅一次的校验修复。只修复问题列表指出的字段，保留其余内容，
            仍然只输出一个完整可解析的 FinancialNarrativeDraft JSON。
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @FunctionalInterface
    interface ModelInvoker {
        FinancialNarrativeDraft invoke(String systemPrompt, String userPrompt) throws Exception;
    }

    private final ModelInvoker invoker;
    private final String modelName;

    public SpringAiFinancialNarrativeGenerator(ChatClient client, String modelName) {
        this((systemPrompt, userPrompt) -> client.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .entity(FinancialNarrativeDraft.class), modelName);
        Objects.requireNonNull(client, "client is required");
    }

    SpringAiFinancialNarrativeGenerator(ModelInvoker invoker, String modelName) {
        this.invoker = Objects.requireNonNull(invoker, "invoker is required");
        this.modelName = modelName == null || modelName.isBlank()
                ? "configured-financial-model" : modelName.trim();
    }

    @Override
    public FinancialNarrativeDraft generate(FinancialEvidencePackage pack) throws Exception {
        Objects.requireNonNull(pack, "pack is required");
        return invoker.invoke(SYSTEM_PROMPT, generationPrompt(pack));
    }

    @Override
    public FinancialNarrativeDraft repair(FinancialEvidencePackage pack,
            FinancialNarrativeDraft draft, List<String> issues) throws Exception {
        Objects.requireNonNull(pack, "pack is required");
        Objects.requireNonNull(draft, "draft is required");
        List<String> safeIssues = issues == null ? List.of() : List.copyOf(issues);
        String userPrompt = "以下是财务证据包 JSON：\n" + MAPPER.writeValueAsString(pack)
                + "\n\n这是上一次草稿 JSON：\n" + MAPPER.writeValueAsString(draft)
                + "\n\n确定性校验发现的问题列表：\n" + MAPPER.writeValueAsString(safeIssues)
                + "\n\n请只修复上述问题，返回完整可解析的 FinancialNarrativeDraft JSON。";
        return invoker.invoke(REPAIR_SYSTEM_PROMPT, userPrompt);
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private static String generationPrompt(FinancialEvidencePackage pack) throws Exception {
        return "以下是证券 " + pack.securityCode() + " 的财务证据包。请严格按照系统约束生成"
                + " FinancialNarrativeDraft JSON：\n" + MAPPER.writeValueAsString(pack);
    }
}
