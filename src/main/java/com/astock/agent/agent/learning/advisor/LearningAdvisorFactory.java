package com.astock.agent.agent.learning.advisor;

import com.astock.agent.agent.learning.LearningAgentProperties;
import com.astock.agent.agent.learning.memory.ConversationMemoryService;
import com.astock.agent.agent.learning.rag.ResearchRetriever;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * 创建学习型 Agent 的 Advisor 链。
 *
 * <p>Advisor 负责补充检索上下文和 trace，不负责扩大工具权限。按证券代码创建的链可以
 * 把检索范围限定在当前股票，避免跨股票证据混入回答。</p>
 */
public final class LearningAdvisorFactory {

    private final LearningAgentProperties properties;
    private final ConversationMemoryService memory;
    private final ResearchRetriever retriever;

    public LearningAdvisorFactory(
            LearningAgentProperties properties,
            ConversationMemoryService memory,
            ResearchRetriever retriever) {
        this.properties = properties;
        this.memory = memory;
        this.retriever = retriever;
    }

    public List<Advisor> create(String securityCode) {
        // 顺序很重要：先准备 RAG 上下文，再由 TraceAdvisor 记录最终链路信息。
        if (!properties.advisorEnabled()) {
            return List.of();
        }
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(new TraceAdvisor());
        if (properties.memoryEnabled() && memory != null) {
            advisors.add(MessageChatMemoryAdvisor.builder(memory.chatMemory()).build());
        }
        if (properties.ragEnabled() && retriever != null) {
            advisors.add(new RagAdvisor(retriever, securityCode, properties.topK()));
        }
        return List.copyOf(advisors);
    }

    public List<Advisor> create() {
        return create("");
    }

}
