package com.astock.agent.agent.learning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.agent.learning.embedding.DeterministicEmbeddingModel;
import com.astock.agent.agent.learning.rag.ResearchRetriever;
import com.astock.agent.agent.learning.vector.InMemoryResearchVectorStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchMcpToolProviderTest {

    @Test
    void exposesBoundedToolsAndExecutesValidatedStockCode() {
        var provider = new ResearchMcpToolProvider(
                new ResearchRetriever(new InMemoryResearchVectorStore(new DeterministicEmbeddingModel(16))),
                code -> Map.of("code", code, "status", "offline"));

        assertThat(provider.tools()).extracting(McpToolDescriptor::name)
                .containsExactly("search_stock", "get_research_snapshot", "get_technical_analysis", "search_knowledge");
        assertThat(provider.execute("get_research_snapshot", Map.of("code", "600519")))
                .extracting(McpToolExecution::success).isEqualTo(true);
    }

    @Test
    void rejectsUrlsShellAndUnknownToolsBeforeDelegation() {
        var provider = new ResearchMcpToolProvider(
                new ResearchRetriever(new InMemoryResearchVectorStore(new DeterministicEmbeddingModel(16))),
                code -> {
                    throw new AssertionError("unsafe input reached delegate");
                });

        assertThat(provider.execute("get_research_snapshot", Map.of("code", "https://evil.test")))
                .extracting(McpToolExecution::errorCode).isEqualTo("INVALID_SECURITY_CODE");
        assertThat(provider.execute("get_research_snapshot", Map.of("code", "600519;whoami")))
                .extracting(McpToolExecution::errorCode).isEqualTo("INVALID_SECURITY_CODE");
        assertThat(provider.execute("unknown", Map.of()))
                .extracting(McpToolExecution::errorCode).isEqualTo("UNKNOWN_TOOL");
    }
}
