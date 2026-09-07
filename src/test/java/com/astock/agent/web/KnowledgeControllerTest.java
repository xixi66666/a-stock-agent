package com.astock.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astock.agent.knowledge.BookKnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KnowledgeControllerTest {
    @Test
    void rejectsUnboundedRequestsAndSeparatesBooksAndMissingEvidence() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(new BookKnowledgeService()))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        for (String limit : new String[] {"0", "11", "-1", "abc"}) {
            mvc.perform(get("/api/knowledge/search").param("q", "孕线").param("limit", limit))
                    .andExpect(status().isBadRequest());
        }
        for (String query : new String[] {" ", "a".repeat(201)}) {
            mvc.perform(get("/api/knowledge/search").param("q", query)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/knowledge/search")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/knowledge/search").param("q", "孕线").param("bookId", "unknown"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/knowledge/search").param("q", "孕线").param("bookId", "marks"))
                .andExpect(jsonPath("$.status").value("NO_MATCH"));
        mvc.perform(get("/api/knowledge/search").param("q", "孕线的影线可以越界吗").param("bookId", "marks"))
                .andExpect(jsonPath("$.status").value("NO_MATCH"));
        mvc.perform(get("/api/knowledge/search").param("q", "量子纠缠xyz"))
                .andExpect(jsonPath("$.matches").isEmpty());
        mvc.perform(get("/api/knowledge/search").param("q", "信贷周期").param("bookId", "marks"))
                .andExpect(jsonPath("$.matches[0].entry.id").value("marks-credit"))
                .andExpect(jsonPath("$.matches[0].entry.requiredEvidence").isNotEmpty());
        mvc.perform(get("/api/knowledge/search").param("q", "判断").param("bookId", "naval"))
                .andExpect(jsonPath("$.matches[0].entry.id").value("naval-judgment"));
        mvc.perform(get("/api/knowledge/entries/nison-harami"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("nison-harami"));
        mvc.perform(get("/api/knowledge/entries/missing")).andExpect(status().isNotFound());
    }

    @Test
    void retrievesChineseAndEnglishHaramiWithPortableChapterCitations() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(new BookKnowledgeService()))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        for (String query : new String[] {"孕线的影线可以越界吗", "harami"}) {
            mvc.perform(get("/api/knowledge/search").param("q", query).param("limit", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("MATCHED"))
                    .andExpect(jsonPath("$.matches[0].entry.id").value("nison-harami"))
                    .andExpect(jsonPath("$.matches[0].entry.chapter").value("第六章 其他反转形态"))
                    .andExpect(jsonPath("$.matches[0].entry.sourceLocator").value("content/chapters/012-section-012.md"))
                    .andExpect(jsonPath("$.matches[0].entry.limitations").isNotEmpty());
        }
    }
}
