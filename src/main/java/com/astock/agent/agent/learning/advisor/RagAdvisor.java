package com.astock.agent.agent.learning.advisor;

import com.astock.agent.agent.learning.rag.RagContext;
import com.astock.agent.agent.learning.rag.ResearchRetriever;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

public final class RagAdvisor implements CallAdvisor {

    public static final String CONTEXT_KEY = "learning.ragContext";
    private final ResearchRetriever retriever;
    private final String securityCode;
    private final int topK;

    public RagAdvisor(ResearchRetriever retriever, String securityCode, int topK) {
        this.retriever = retriever;
        this.securityCode = securityCode;
        this.topK = topK;
    }

    @Override
    public String getName() {
        return "RagAdvisor";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        RagContext context = retriever.retrieve(request.prompt().getContents(), securityCode, topK);
        ChatClientRequest enriched = request.mutate()
                .prompt(request.prompt().augmentUserMessage("\n\n检索证据：\n" + context.formattedPromptContext()))
                .context(CONTEXT_KEY, context)
                .build();
        return chain.nextCall(enriched);
    }
}
