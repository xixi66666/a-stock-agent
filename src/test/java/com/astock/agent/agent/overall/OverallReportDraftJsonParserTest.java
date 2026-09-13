package com.astock.agent.agent.overall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OverallReportDraftJsonParserTest {

    @Test
    void parsesPlainJsonObject() {
        OverallReportDraft draft = OverallReportDraftJsonParser.parse(validJson());

        assertThat(draft.overallConclusion()).isEqualTo("结论");
        assertThat(draft.bullishEvidence()).containsExactly("正向证据");
        assertThat(draft.scenarios()).isEqualTo(Map.of("stronger", "强", "neutral", "中", "weaker", "弱"));
        assertThat(draft.sourceReferences()).hasSize(1);
        assertThat(draft.sourceReferences().getFirst().provider()).isEqualTo("Tencent Finance");
        assertThat(draft.disclaimer()).isEqualTo("仅供学习研究，不构成投资建议");
    }

    @Test
    void parsesFencedJsonSurroundedByProse() {
        String response = "好的，以下是根据快照生成的报告：\n```json\n" + validJson()
                + "\n```\n希望对你有帮助。";

        OverallReportDraft draft = OverallReportDraftJsonParser.parse(response);

        assertThat(draft.overallConclusion()).isEqualTo("结论");
    }

    @Test
    void parsesJsonWithTrailingProse() {
        OverallReportDraft draft = OverallReportDraftJsonParser.parse(validJson() + "\n以上为报告内容。");

        assertThat(draft.overallConclusion()).isEqualTo("结论");
    }

    @Test
    void keepsBraceCharactersInsideStringValues() {
        String json = validJson().replace("结论", "引用 {分块} 与 \\\"引号\\\" 的结论");

        OverallReportDraft draft = OverallReportDraftJsonParser.parse(json);

        assertThat(draft.overallConclusion()).isEqualTo("引用 {分块} 与 \"引号\" 的结论");
    }

    @Test
    void toleratesTrailingCommaAndUnknownFields() {
        String json = validJson().replace("\"disclaimer\": \"仅供学习研究，不构成投资建议\"",
                "\"disclaimer\": \"仅供学习研究，不构成投资建议\", \"extraField\": 123,");

        OverallReportDraft draft = OverallReportDraftJsonParser.parse(json);

        assertThat(draft.disclaimer()).isEqualTo("仅供学习研究，不构成投资建议");
    }

    @Test
    void stripsThinkingTagsBeforeExtraction() {
        String response = "<thinking>分析 {的内部草稿} 不应被解析</thinking>\n" + validJson();

        OverallReportDraft draft = OverallReportDraftJsonParser.parse(response);

        assertThat(draft.overallConclusion()).isEqualTo("结论");
    }

    @Test
    void rejectsEmptyResponseWithClassifierCompatibleMessage() {
        assertThatThrownBy(() -> OverallReportDraftJsonParser.parse("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void rejectsTruncatedJson() {
        String truncated = validJson().substring(0, validJson().length() / 2);

        assertThatThrownBy(() -> OverallReportDraftJsonParser.parse(truncated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON")
                .hasMessageContaining("截断");
    }

    @Test
    void rejectsResponseWithoutJsonObject() {
        assertThatThrownBy(() -> OverallReportDraftJsonParser.parse("抱歉，我无法生成报告。"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void rejectsJsonWithoutDraftFields() {
        assertThatThrownBy(() -> OverallReportDraftJsonParser.parse("{\"foo\": 1}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OverallReportDraft");
    }

    private static String validJson() {
        return """
                {
                  "overallConclusion": "结论",
                  "dataQualitySummary": "质量",
                  "companyAndFundamentals": "公司",
                  "technicalAndCapital": "技术",
                  "valuationAndIndustry": "估值",
                  "eventsAndSentiment": "事件",
                  "bullishEvidence": ["正向证据"],
                  "bearishEvidence": ["反向证据"],
                  "riskFactors": [],
                  "scenarios": {"stronger": "强", "neutral": "中", "weaker": "弱"},
                  "conflictsAndMissingData": [],
                  "sourceReferences": [{
                    "section": "quote",
                    "provider": "Tencent Finance",
                    "sourceUrl": "https://qt.gtimg.cn/q=sh600519",
                    "fetchedAt": "2026-09-13T15:11:34.136687600Z"
                  }],
                  "disclaimer": "仅供学习研究，不构成投资建议"
                }
                """;
    }
}
