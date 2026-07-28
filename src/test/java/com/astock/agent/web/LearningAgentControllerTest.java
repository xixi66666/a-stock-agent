package com.astock.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.agent.learning.LearningAgentFacade;
import com.astock.agent.agent.learning.LearningAgentProperties;
import com.astock.agent.agent.learning.embedding.DeterministicEmbeddingModel;
import com.astock.agent.agent.learning.mcp.ResearchMcpToolProvider;
import com.astock.agent.agent.learning.memory.ConversationMemoryService;
import com.astock.agent.agent.learning.rag.ResearchKnowledgeIndexer;
import com.astock.agent.agent.learning.rag.ResearchRetriever;
import com.astock.agent.agent.learning.vector.InMemoryResearchVectorStore;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LearningAgentControllerTest {

    @Test
    void returnsOfflineLearningResponse() throws Exception {
        var vectorStore = new InMemoryResearchVectorStore(new DeterministicEmbeddingModel(16));
        var retriever = new ResearchRetriever(vectorStore);
        var memory = new ConversationMemoryService(MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(8).build());
        var facade = new LearningAgentFacade(
                new LearningAgentProperties(true, true, true, true, true, 8, 4, 16), memory,
                new ResearchKnowledgeIndexer(vectorStore), retriever,
                new ResearchMcpToolProvider(retriever, code -> Map.of("code", code)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LearningAgentController(facade)).build();

        mvc.perform(post("/api/agent/learning/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\",\"message\":\"解释盈利\",\"conversationId\":\"demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelUsed").value("offline-deterministic"))
                .andExpect(jsonPath("$.disclaimer").value("仅供学习研究，不构成投资建议"));
    }

    @Test
    void rejectsInvalidCode() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LearningAgentController(null)).build();

        mvc.perform(post("/api/agent/learning/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABC\",\"message\":\"问题\",\"conversationId\":\"demo\"}"))
                .andExpect(status().isBadRequest());
    }
}
