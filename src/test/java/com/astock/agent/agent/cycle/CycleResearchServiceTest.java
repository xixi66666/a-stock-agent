package com.astock.agent.agent.cycle;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.astock.agent.web.CycleController;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

class CycleResearchServiceTest {
    @Test
    void runningTaskIsReusedAndSuccessfulReportSurvivesServiceRestart() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory(Path.of("target"), "cycle-store-");
        var properties = new CycleProperties("missing", "missing", "python", directory.toString());
        var client = org.springframework.ai.chat.client.ChatClient.create(mock(org.springframework.ai.chat.model.ChatModel.class));
        var registry = new NamedChatClientRegistry(Map.of("test", new NamedChatClientRegistry.NamedModel(client, "test-model")), Map.of("cycle-report", "test"));
        var library = mock(CycleLibrary.class);
        when(library.instructions()).thenReturn("测试技能");
        var tools = mock(StockAgentTools.class);
        when(tools.getResearchSnapshot("600519")).thenReturn(com.astock.agent.analysis.StockResearchSnapshot.empty(com.astock.agent.marketdata.model.SecurityId.parse("600519")));
        var gate = new java.util.concurrent.CountDownLatch(1);
        CycleReport report = new CycleReport("测试保存结果", java.util.List.of(), java.util.List.of(), java.util.List.of(), "", java.util.List.of(), java.util.List.of());
        try (var service = new CycleResearchService(registry, tools, properties, library, (model, session) -> {
            gate.await(); return report;
        }, java.time.Duration.ofSeconds(10))) {
            var task = service.start("600519", null);
            assertThat(service.start("600519", "test").id()).isEqualTo(task.id());
            gate.countDown();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> service.get(task.id()).status().equals("COMPLETED"));
            assertThat(service.get(task.id()).error()).isNull();
            verify(tools, times(1)).getResearchSnapshot("600519");
        } finally { gate.countDown(); }
        try (var restored = new CycleResearchService(registry, tools, properties)) {
            assertThat(restored.latest("600519").report().conclusion()).isEqualTo("测试保存结果");
            assertThat(restored.latest("600519").modelName()).isEqualTo("test-model");
        }
    }

    @Test
    void timeoutCannotBeOverwrittenByLateModelCompletion() throws Exception {
        var props = new CycleProperties("missing", "missing", "python", "target/cycle-timeout");
        var client = org.springframework.ai.chat.client.ChatClient.create(mock(org.springframework.ai.chat.model.ChatModel.class));
        var registry = new NamedChatClientRegistry(Map.of("test", new NamedChatClientRegistry.NamedModel(client, "test")), Map.of("cycle-report", "test"));
        var library = mock(CycleLibrary.class);
        when(library.instructions()).thenReturn("测试技能");
        var tools = mock(StockAgentTools.class);
        when(tools.getResearchSnapshot(anyString())).thenReturn(com.astock.agent.analysis.StockResearchSnapshot.empty(com.astock.agent.marketdata.model.SecurityId.parse("600519")));
        var finished = new java.util.concurrent.CountDownLatch(1);
        try (var service = new CycleResearchService(registry, tools, props, library, (model, session) -> {
            try { Thread.sleep(10_000); } catch (InterruptedException expected) { /* 模拟忽略中断后返回 */ }
            finally { finished.countDown(); }
            return new CycleReport("迟到结果", null, null, null, null, null, null);
        }, java.time.Duration.ofMillis(150))) {
            var task = service.start("600519", null);
            assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(service.get(task.id()).status()).isEqualTo("FAILED");
            assertThat(service.get(task.id()).error()).contains("超时");
            assertThat(service.get(task.id()).report()).isNull();
        }
    }
    @Test
    void missingModelIsSectionLocalAndInvalidCodeNeverStartsWork() throws Exception {
        StockAgentTools tools = mock(StockAgentTools.class);
        CycleProperties properties = new CycleProperties("missing", "missing", "python", "target/cycle-test-reports");
        try (var service = new CycleResearchService(new NamedChatClientRegistry(Map.of(), Map.of()), tools, properties)) {
            var mvc = MockMvcBuilders.standaloneSetup(new CycleController(service)).build();
            mvc.perform(post("/api/agent/cycle/tasks").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"600519\"}"))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.error").value("未配置周期研究模型，请配置 cycle-report 角色或选择可用模型"));
            mvc.perform(post("/api/agent/cycle/tasks").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"../x\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/agent/cycle/tasks/00000000-0000-0000-0000-000000000000"))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(tools);
        }
    }
}
