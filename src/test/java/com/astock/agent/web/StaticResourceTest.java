package com.astock.agent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.ai.model.chat=none")
@AutoConfigureMockMvc
class StaticResourceTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void servesActualResearchWorkbenchAtRoot() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        String html = mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("A 股智能研究台")
                .contains("开始研究")
                .doesNotContain("book-knowledge");
    }

    @Test
    void doesNotExposeBookKnowledgeQueryFeature() throws Exception {
        mvc.perform(get("/api/knowledge/search").param("q", "孕线"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/knowledge/entries/nison-harami"))
                .andExpect(status().isNotFound());

        String script = mvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(script).doesNotContain("activateBookKnowledge");
    }

    @Test
    void servesResearchWorkbenchModuleScript() throws Exception {
        String script = mvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(script)
                .contains("stockApi.snapshot")
                .contains("data-symbol")
                .contains("getSelectedModelId")
                .contains("model-picker");
    }

    @Test
    void servesFinRobotResearchApiModule() throws Exception {
        String script = mvc.perform(get("/js/api.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(script)
                .contains("finRobotModels()")
                .contains("finRobotResearch(code, modelId)")
                .contains("/api/finrobot/models")
                .contains("/api/finrobot/research")
                .contains("aiApi")
                .contains("/api/ai/models")
                .doesNotContain("/api/agent/quant-report")
                .doesNotContain("/api/agent/analyze");
    }

    @Test
    void servesModelSelectionModule() throws Exception {
        String script = mvc.perform(get("/js/model-selection.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(script)
                .contains("astock.selectedModelId")
                .contains("getSelectedModelId")
                .contains("setSelectedModelId")
                .doesNotContain("apiKey");
    }

    @Test
    void servesFinRobotViewTemplate() throws Exception {
        String script = mvc.perform(get("/js/views.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(script)
                .doesNotContain("finrobot-model-select")
                .contains("finrobot-output")
                .contains("run-finrobot")
                .doesNotContain("DeepSeek 总体报告");
    }

    @Test
    void servesDerivedMarketViewWithoutProviderCredentials() throws Exception {
        String script = mvc.perform(get("/js/derived-market-view.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(script)
                .contains("同行估值对比")
                .contains("资金流窗口汇总")
                .doesNotMatch("(?i)(api[-_]?key|authorization|bearer|token|secret)");
    }

    @Test
    void labelsCandlestickOccurrenceDateSeparatelyFromAnalysisCutoff() throws Exception {
        String script = mvc.perform(get("/js/candlestick-view.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(script)
                .contains("形态发生日：")
                .contains("分析截止：");
    }
}
