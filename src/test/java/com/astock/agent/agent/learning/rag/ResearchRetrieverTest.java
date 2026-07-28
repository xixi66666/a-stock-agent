package com.astock.agent.agent.learning.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.agent.learning.embedding.DeterministicEmbeddingModel;
import com.astock.agent.agent.learning.vector.InMemoryResearchVectorStore;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.SecurityId;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchRetrieverTest {

    @Test
    void returnsTopKContextWithSourceAndExplicitMissingState() {
        var store = new InMemoryResearchVectorStore(new DeterministicEmbeddingModel(16));
        var indexer = new ResearchKnowledgeIndexer(store);
        var provenance = new Provenance(
                "Fixture", URI.create("https://example.test/news"), Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T01:00:00Z"), false, null);
        indexer.index(SecurityId.parse("600519"), List.of(
                new ResearchKnowledgeIndexer.ResearchEvidence("news", "盈利增长", provenance)));
        var retriever = new ResearchRetriever(store);

        RagContext context = retriever.retrieve("盈利", "600519", 1);
        RagContext missing = retriever.retrieve("盈利", "000001", 1);

        assertThat(context.documents()).hasSize(1);
        assertThat(context.formattedPromptContext()).contains("Fixture", "https://example.test/news");
        assertThat(context.missingReason()).isEmpty();
        assertThat(missing.documents()).isEmpty();
        assertThat(missing.missingReason()).contains("No matching research evidence");
    }
}
