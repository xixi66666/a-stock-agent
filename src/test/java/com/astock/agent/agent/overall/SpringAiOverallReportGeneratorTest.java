package com.astock.agent.agent.overall;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.SecurityId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SpringAiOverallReportGeneratorTest {
    @Test
    void suppliesBookMethodologyInGenerationAndRepairWithoutExpandingMarketFactBoundary() throws Exception {
        AtomicReference<String> system = new AtomicReference<>();
        AtomicReference<String> user = new AtomicReference<>();
        var generator = new SpringAiOverallReportGenerator((s, u) -> {
            system.set(s); user.set(u); return validDraft();
        }, "test-model");
        var snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        generator.generate(snapshot);
        assertThat(user.get()).contains("书本方法上下文", "nison-reversal", "nison-volume",
                "nison-oscillator", "requiredEvidence")
                .doesNotContain("nison-continuation-patterns", "marks-position", "naval-clear-thinking");
        assertThat(system.get()).contains("不是当前市场事实", "书名和章节")
                .doesNotContain("当前周期位置", "纳瓦尔");
        generator.repair(snapshot, validDraft(), List.of("UNSUPPORTED_NUMBER"));
        assertThat(user.get()).contains("书本方法上下文", "nison-reversal", "nison-volume")
                .doesNotContain("nison-continuation-patterns", "marks-position", "naval-clear-thinking");
    }


    @Test
    void sendsCompleteSnapshotAndProfessionalConstraints() throws Exception {
        AtomicReference<String> system = new AtomicReference<>();
        AtomicReference<String> user = new AtomicReference<>();
        OverallReportDraft expected = validDraft();
        SpringAiOverallReportGenerator generator = new SpringAiOverallReportGenerator(
                (systemText, userText) -> {
                    system.set(systemText);
                    user.set(userText);
                    return expected;
                }, "deepseek-chat");

        generator.generate(StockResearchSnapshot.empty(SecurityId.parse("600519")));

        assertThat(user.get()).contains(
                "\"quote\"", "\"bars\"", "\"technical\"", "\"sectors\"",
                "\"industryValuation\"", "\"fundFlow\"", "\"capital\"",
                "\"fundamentals\"", "\"research\"", "\"news\"", "\"announcements\"",
                "\"quality\"", "\"fetchedAt\"");
        assertThat(system.get()).contains(
                "唯一事实边界", "HEALTHY", "UNAVAILABLE", "正反证据",
                "不得输出买入", "只返回 OverallReportDraft 对应的 JSON",
                "不要输出快照之外的数字", "不要使用阿拉伯数字编号");
        assertThat(generator.modelName()).isEqualTo("deepseek-chat");
    }

    @Test
    void repairIncludesOriginalSnapshotDraftAndValidationIssues() throws Exception {
        AtomicReference<String> user = new AtomicReference<>();
        OverallReportDraft expected = validDraft();
        SpringAiOverallReportGenerator generator = new SpringAiOverallReportGenerator(
                (systemText, userText) -> {
                    user.set(userText);
                    return expected;
                }, "deepseek-chat");
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));

        generator.repair(snapshot, expected, List.of("TRADE_INSTRUCTION", "UNSUPPORTED_NUMBER"));

        assertThat(user.get()).contains("上一次草稿", "TRADE_INSTRUCTION", "UNSUPPORTED_NUMBER",
                "\"quote\"", "\"fetchedAt\"", "删除所有未经快照原样出现的数字");
    }

    private static OverallReportDraft validDraft() {
        return new OverallReportDraft(
                "核心数据不足，无法形成高强度结论",
                "quote 与 bars 均不可用",
                "基本面不可用", "技术与资金不可用", "估值与行业不可用", "事件不可用",
                List.of(), List.of(), List.of(), Map.of(),
                List.of("quote 不可用", "bars 不可用"), List.of(),
                OverallResearchReport.REQUIRED_DISCLAIMER);
    }
}
