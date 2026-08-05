package com.astock.agent.agent.learning.rag;

import com.astock.agent.agent.learning.vector.InMemoryResearchVectorStore;
import com.astock.agent.agent.learning.vector.VectorSearchResult;
import java.util.List;
import java.util.Objects;

/**
 * 从向量库检索与问题相关的研究证据。
 *
 * <p>securityCode 是硬过滤条件，topK 是上限；检索器不会跨股票拼接上下文，也不会在找不到
 * 证据时编造答案。</p>
 */
public final class ResearchRetriever {

    private final InMemoryResearchVectorStore vectorStore;

    public ResearchRetriever(InMemoryResearchVectorStore vectorStore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
    }

    public RagContext retrieve(String question, String securityCode, int topK) {
        // 先校验问题和代码，再让向量库按证券过滤并排序；空结果返回明确的 missing 状态。
        List<VectorSearchResult> documents = vectorStore.search(question, securityCode, topK);
        if (documents.isEmpty()) {
            return RagContext.missing("No matching research evidence");
        }
        String context = documents.stream()
                .map(document -> "[" + document.metadata().getOrDefault("section", "unknown") + "] "
                        + document.text() + " | source=" + document.metadata().getOrDefault("provider", "unknown")
                        + " | url=" + document.metadata().getOrDefault("sourceUrl", "unknown")
                        + " | score=" + String.format(java.util.Locale.ROOT, "%.3f", document.score()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return new RagContext(documents, context, "");
    }
}
