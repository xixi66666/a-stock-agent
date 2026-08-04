package com.astock.agent.agent.overall;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OverallResearchReportTest {

    @Test
    void preservesModelDisclaimerWithoutServerOverride() {
        OverallReportDraft draft = new OverallReportDraft(
                "结论", "数据质量", "公司与基本面", "技术与资金", "估值与行业", "事件与情绪",
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), "模型自定义说明");

        OverallResearchReport report = OverallResearchReport.from(
                draft, "deepseek-chat", Instant.EPOCH, Instant.EPOCH, "overall-v1");

        assertThat(report.disclaimer()).isEqualTo("模型自定义说明");
    }
}
