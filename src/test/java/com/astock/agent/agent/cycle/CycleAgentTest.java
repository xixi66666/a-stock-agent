package com.astock.agent.agent.cycle;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.SecurityId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CycleAgentTest {
    @Test
    void completesLongResearchWithRealToolRoundTripsAndRejectsUnsupportedCitations() throws Exception {
        CycleLibrary library = mock(CycleLibrary.class);
        when(library.instructions()).thenReturn("检索后阅读多个章节");
        when(library.search(anyString())).thenReturn(List.of(new CycleLibrary.Hit("017-13.md", "应对周期", "测试方法")));
        when(library.topics()).thenReturn("017-13.md / 019-15.md");
        when(library.chapter("017-13.md", 0)).thenReturn(new CycleLibrary.Page("017-13.md", "应对周期", "测试正文", 0, -1));
        when(library.chapter("019-15.md", 0)).thenReturn(new CycleLibrary.Page("019-15.md", "局限性", "测试正文", 0, -1));
        var actions = new java.util.ArrayList<AssistantMessage.ToolCall>();
        for (int i = 0; i < 13; i++) actions.add(tool("s" + i, "cycleSearch", "{\"query\":\"周期" + i + "\"}"));
        actions.add(tool("t", "cycleReadTopics", "{}"));
        actions.add(tool("c1", "cycleReadChapter", "{\"chapterId\":\"017-13.md\",\"offset\":0}"));
        actions.add(tool("c2", "cycleReadChapter", "{\"chapterId\":\"019-15.md\",\"offset\":0}"));
        for (String section : CycleSession.SECTIONS) actions.add(tool(section, "cycleReadEvidence", "{\"section\":\"" + section + "\"}"));
        CycleReport expected = new CycleReport("测试结论", CycleSession.DIMENSIONS.stream().map(id ->
                new CycleReport.Dimension(id, id, "书中方法概括", List.of("017-13.md"), List.of(), "证据不足")).toList(),
                List.of("矛盾"), List.of(new CycleReport.Scenario("偏强条件", "解释"), new CycleReport.Scenario("延续条件", "解释"),
                        new CycleReport.Scenario("偏弱条件", "解释")), "一般校准原则", List.of("观察项"), List.of("资料缺失"));
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(expected);
        ChatModel model = mock(ChatModel.class);
        AtomicInteger rounds = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            int round = rounds.getAndIncrement();
            if (round > 0) assertThat(((Prompt) invocation.getArgument(0)).getInstructions()).anyMatch(ToolResponseMessage.class::isInstance);
            return new ChatResponse(List.of(new Generation(round < actions.size()
                    ? AssistantMessage.builder().content("").toolCalls(List.of(actions.get(round))).build()
                    : new AssistantMessage(json))));
        });
        var session = new CycleSession(library, StockResearchSnapshot.empty(SecurityId.parse("600519")), stage -> {});
        assertThat(new CycleAgent().run(ChatClient.create(model), session)).isEqualTo(expected);
        assertThat(session.trace()).hasSize(actions.size());
        assertThat(session.chapters()).hasSize(2);
        var dimensions = new java.util.ArrayList<>(expected.dimensions());
        var original = dimensions.getFirst();
        dimensions.set(0, new CycleReport.Dimension(original.id(), original.title(), original.bookView(), List.of("999-99.md"), List.of(), "分析"));
        assertThatThrownBy(() -> session.validate(new CycleReport(expected.conclusion(), dimensions, expected.conflicts(), expected.scenarios(), expected.calibration(), expected.watchItems(), expected.limitations())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("原书引用");
        dimensions.set(0, new CycleReport.Dimension(original.id(), original.title(), original.bookView(), original.chapterIds(),
                List.of(new CycleReport.Fact("编造的可用事实", List.of("quote"))), "分析"));
        assertThatThrownBy(() -> session.validate(new CycleReport(expected.conclusion(), dimensions, expected.conflicts(), expected.scenarios(), expected.calibration(), expected.watchItems(), expected.limitations())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("市场事实引用");
    }
    private static AssistantMessage.ToolCall tool(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }
    @Test
    void executesModelRequestedBookSearchAndReturnsResultsBeforeAcceptingReport() throws Exception {
        CycleLibrary library = mock(CycleLibrary.class);
        when(library.instructions()).thenReturn("原始技能要求：必须检索原书");
        when(library.search("风险态度")).thenReturn(List.of(new CycleLibrary.Hit("012-08.md", "风险态度周期", "风险补偿")));
        ChatModel model = mock(ChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            if (calls.getAndIncrement() == 0) {
                assertThat(prompt.getContents()).contains("原始技能要求");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "cycleSearch", "{\"query\":\"风险态度\"}"))).build())));
            }
            assertThat(prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(Object::toString).toList().toString()).contains("风险态度周期");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
        });
        CycleSession session = new CycleSession(library,
                StockResearchSnapshot.empty(SecurityId.parse("600519")), stage -> {});
        // 模型确实拿到工具结果，但只检索一次、未读章节，不能生成完成状态。
        assertThatThrownBy(() -> new CycleAgent().run(ChatClient.create(model), session))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("原书");
        verify(library).search("风险态度");
        assertThat(session.trace()).anyMatch(event -> event.action().equals("检索原书"));
        assertThat(calls.get()).isEqualTo(2);
    }
}
