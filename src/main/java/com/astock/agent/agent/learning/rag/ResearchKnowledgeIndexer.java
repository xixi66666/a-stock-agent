package com.astock.agent.agent.learning.rag;

import com.astock.agent.agent.learning.vector.InMemoryResearchVectorStore;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.SecurityId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.document.Document;

/**
 * 把规范化研究证据转成可检索文档并写入进程内向量库。
 *
 * <p>索引项保留 section、security code、Provider 和时间元数据；空文本或无来源证据会跳过，
 * 避免 RAG 返回无法解释的上下文。</p>
 */
public final class ResearchKnowledgeIndexer {

    private final InMemoryResearchVectorStore vectorStore;

    public ResearchKnowledgeIndexer(InMemoryResearchVectorStore vectorStore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
    }

    public IndexResult index(SecurityId security, List<ResearchEvidence> evidence) {
        // 先过滤不具备最小证据条件的项，再统一写入向量库，结果中记录跳过原因。
        Objects.requireNonNull(security, "security");
        List<Document> documents = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (evidence != null) {
            for (ResearchEvidence item : evidence) {
                if (item == null || item.text() == null || item.text().isBlank()) {
                    if (item != null && item.section() != null) {
                        skipped.add(item.section());
                    }
                    continue;
                }
                Provenance source = Objects.requireNonNull(item.provenance(), "provenance");
                String id = security.code() + ":" + item.section() + ":" + source.sourceUrl();
                Map<String, Object> metadata = Map.of(
                        "securityCode", security.code(),
                        "section", item.section(),
                        "provider", source.provider(),
                        "sourceUrl", source.sourceUrl().toString(),
                        "providerTimestamp", String.valueOf(source.providerTimestamp()),
                        "fetchedAt", source.fetchedAt().toString());
                documents.add(new Document(id, item.text().trim(), metadata));
            }
        }
        vectorStore.add(documents);
        return new IndexResult(documents.size(), List.copyOf(skipped), List.copyOf(documents));
    }

    public record ResearchEvidence(String section, String text, Provenance provenance) {
        public ResearchEvidence {
            if (section == null || section.isBlank()) {
                throw new IllegalArgumentException("Research section must not be blank");
            }
        }
    }

    public record IndexResult(int indexed, List<String> skippedSections, List<Document> documents) {
        public IndexResult {
            skippedSections = skippedSections == null ? List.of() : List.copyOf(skippedSections);
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }
}
