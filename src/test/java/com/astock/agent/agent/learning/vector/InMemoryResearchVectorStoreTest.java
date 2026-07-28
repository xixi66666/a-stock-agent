package com.astock.agent.agent.learning.vector;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.agent.learning.embedding.DeterministicEmbeddingModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class InMemoryResearchVectorStoreTest {

    @Test
    void ranksSimilarDocumentsAndFiltersBySecurityCode() {
        var store = new InMemoryResearchVectorStore(new DeterministicEmbeddingModel(32));
        store.add(List.of(
                new Document("600519 盈利增长和收入", Map.of("securityCode", "600519", "section", "fundamentals")),
                new Document("000001 技术指标和趋势", Map.of("securityCode", "000001", "section", "technical"))));

        List<VectorSearchResult> results = store.search("600519 盈利", "600519", 2);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().text()).contains("盈利");
        assertThat(results.getFirst().metadata()).containsEntry("section", "fundamentals");
        assertThat(results.getFirst().score()).isBetween(0.0, 1.0);
    }

    @Test
    void clearsIndexedDocuments() {
        var store = new InMemoryResearchVectorStore(new DeterministicEmbeddingModel(8));
        store.add(List.of(new Document("公告", Map.of("securityCode", "600519"))));

        store.clear();

        assertThat(store.search("公告", null, 4)).isEmpty();
    }
}
