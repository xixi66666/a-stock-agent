package com.astock.agent.agent.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;

/** Spring AI 适配层：只负责有界证据包与结构化叙述的转换。 */
public final class SpringAiNarrativeGenerator implements NarrativeGenerator {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String SYSTEM_PROMPT = """
            你是A股研究报告叙述助手。只能依据用户提供的有界证据包写中文叙述，不能改变方向和证据状态。
            每个模块必须解释主要信号、方法依据、反证和数据限制，并引用证据ID，例如[quote-price]。
            不得补充证据包之外的事实或数字，不得删除冲突、缺失数据和失效条件。
            不得输出买卖、仓位、目标价、收益保证或个性化建议。
            只返回ReportNarrativeDraft结构。
            """;

    private final ChatClient chatClient;
    private final String modelName;

    public SpringAiNarrativeGenerator(ChatClient chatClient, String modelName) {
        if (chatClient == null) throw new IllegalArgumentException("chatClient is required");
        this.chatClient = chatClient;
        this.modelName = modelName == null || modelName.isBlank() ? "configured-chat-model" : modelName;
    }

    @Override
    public ReportNarrativeDraft generate(ReportEvidencePackage evidence) throws Exception {
        return chatClient.prompt().system(SYSTEM_PROMPT).user(MAPPER.writeValueAsString(evidence))
                .call().entity(ReportNarrativeDraft.class);
    }

    @Override
    public ReportNarrativeDraft repair(ReportEvidencePackage evidence, ReportNarrativeDraft draft,
            java.util.List<String> issues) throws Exception {
        String repairSystem = SYSTEM_PROMPT + "\n上一次叙述未通过校验。只修复列出的字段问题，仍然只能使用证据包中的内容。"
                + "不得输出任何交易指令；证据引用必须使用证据包中的真实 ID；必须保留冲突。";
        String repairUser = "证据包：" + MAPPER.writeValueAsString(evidence)
                + "\n上一次草稿：" + MAPPER.writeValueAsString(draft)
                + "\n校验问题：" + String.join(",", issues == null ? java.util.List.of() : issues);
        return chatClient.prompt().system(repairSystem).user(repairUser)
                .call().entity(ReportNarrativeDraft.class);
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
