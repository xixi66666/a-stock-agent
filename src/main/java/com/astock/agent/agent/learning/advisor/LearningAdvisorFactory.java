package com.astock.agent.agent.learning.advisor;

import com.astock.agent.agent.learning.LearningAgentProperties;
import com.astock.agent.agent.learning.memory.ConversationMemoryService;
import com.astock.agent.agent.learning.rag.ResearchRetriever;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

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
