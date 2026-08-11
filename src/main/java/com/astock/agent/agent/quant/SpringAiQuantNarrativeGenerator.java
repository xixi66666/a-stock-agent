package com.astock.agent.agent.quant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;

/** 可选的大模型适配器。模型只组织事实包中的文字，不负责计算或补充数据。 */
public final class SpringAiQuantNarrativeGenerator implements QuantNarrativeGenerator {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    static final String SYSTEM_PROMPT = """
            你是专业量化研究报告的文字编辑。只能使用用户提供的 QuantReportFacts 中已有数字、日期、来源和状态。
            只能输出 QuantNarrativeDraft JSON；不得计算新数字、编造来源、提出买入卖出或目标价，不得输出评分、排名、置信度或综合结论。
            对 UNAVAILABLE 或 INSUFFICIENT_SAMPLE 数据只能说明缺失及其影响，不能写成确定性判断。
            """;

    private final ChatClient chatClient;
    private final String modelName;

    public SpringAiQuantNarrativeGenerator(ChatClient chatClient, String modelName) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient is required");
        this.modelName = modelName == null || modelName.isBlank() ? "configured-quant-model" : modelName.trim();
    }

    @Override
    public QuantNarrativeDraft generate(QuantReportFacts facts) throws Exception {
        Objects.requireNonNull(facts, "facts");
        return chatClient.prompt().system(SYSTEM_PROMPT).user(MAPPER.writeValueAsString(facts))
                .call().entity(QuantNarrativeDraft.class);
    }

    @Override
    public QuantNarrativeDraft repair(QuantReportFacts facts, QuantNarrativeDraft draft,
                                      java.util.List<String> issues) throws Exception {
        String user = "事实包：" + MAPPER.writeValueAsString(facts)
                + "\n原草稿：" + MAPPER.writeValueAsString(draft)
                + "\n校验问题：" + MAPPER.writeValueAsString(issues == null ? java.util.List.of() : issues)
                + "\n只修复校验问题并返回 QuantNarrativeDraft JSON。";
        return chatClient.prompt().system(SYSTEM_PROMPT).user(user).call().entity(QuantNarrativeDraft.class);
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
