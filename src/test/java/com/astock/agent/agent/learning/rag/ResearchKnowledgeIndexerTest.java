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

class ResearchKnowledgeIndexerTest {

    @Test
    void createsDocumentsWithSourceMetadataAndSkipsUnavailableEvidence() {
        var indexer = new ResearchKnowledgeIndexer(new InMemoryResearchVectorStore(
                new DeterministicEmbeddingModel(16)));
        var provenance = new Provenance(
                "Fixture", URI.create("https://example.test/report"), Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T01:00:00Z"), false, null);

        var result = indexer.index(SecurityId.parse("600519"), List.of(
                new ResearchKnowledgeIndexer.ResearchEvidence("fundamentals", "盈利增长", provenance),
                new ResearchKnowledgeIndexer.ResearchEvidence("news", "", provenance)));

        assertThat(result.indexed()).isEqualTo(1);
        assertThat(result.skippedSections()).containsExactly("news");
        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().getFirst().getMetadata())
                .containsEntry("securityCode", "600519")
                .containsEntry("section", "fundamentals")
                .containsEntry("provider", "Fixture")
                .containsEntry("sourceUrl", "https://example.test/report");
    }
}
