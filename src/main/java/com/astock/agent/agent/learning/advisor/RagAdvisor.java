package com.astock.agent.agent.learning.advisor;

import com.astock.agent.agent.learning.rag.RagContext;
import com.astock.agent.agent.learning.rag.ResearchRetriever;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

/**
 * 在模型调用前检索当前证券的研究证据。
 *
 * <p>检索结果放入请求上下文而不是直接拼接成不可追踪的字符串，后续可以同时保留
 * 文档来源、相似度和缺失状态。</p>
 */
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
        // Advisor 不自行调用模型，只负责准备上下文，然后把请求交给下一环节。
        RagContext context = retriever.retrieve(request.prompt().getContents(), securityCode, topK);
        ChatClientRequest enriched = request.mutate()
                .prompt(request.prompt().augmentUserMessage("\n\n检索证据：\n" + context.formattedPromptContext()))
                .context(CONTEXT_KEY, context)
                .build();
        return chain.nextCall(enriched);
    }
}
