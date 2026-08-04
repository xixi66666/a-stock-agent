package com.astock.agent.agent.overall;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.SecurityId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OverallReportServiceTest {

    private final StockResearchSnapshot snapshot =
            StockResearchSnapshot.empty(SecurityId.parse("600519"));

    @Test
    void missingDeepseekReturnsLocalStatusWithoutFetchingSnapshot() {
        AtomicInteger fetches = new AtomicInteger();
        StockAgentTools tools = new StockAgentTools(id -> {
            fetches.incrementAndGet();
            return snapshot;
        });

        OverallReportResponse response = new OverallReportService(null, tools,
                new OverallReportValidator(), new ModelFailureClassifier()).generate("600519");

        assertThat(response.status()).isEqualTo(OverallReportStatus.MODEL_NOT_CONFIGURED);
        assertThat(response.report()).isNull();
        assertThat(response.diagnostic()).isNull();
        assertThat(fetches).hasValue(0);
    }

    @Test
    void validDraftReturnsModelAssistedReport() {
        OverallReportGenerator generator = new OverallReportGenerator() {
            @Override
            public OverallReportDraft generate(StockResearchSnapshot ignored) {
                return validDraft();
            }

            @Override
            public String modelName() {
                return "deepseek-chat";
            }
        };

        OverallReportResponse response = service(generator).generate("600519");

        assertThat(response.status()).isEqualTo(OverallReportStatus.MODEL_ASSISTED);
        assertThat(response.report()).isNotNull();
        assertThat(response.report().modelName()).isEqualTo("deepseek-chat");
        assertThat(response.report().disclaimer()).isEqualTo(OverallResearchReport.REQUIRED_DISCLAIMER);
    }

    @Test
    void invalidDraftReturnsReportWithWarningWithoutRepair() {
        AtomicInteger repairs = new AtomicInteger();
        OverallReportGenerator generator = new OverallReportGenerator() {
            @Override
            public OverallReportDraft generate(StockResearchSnapshot ignored) {
                return invalidDraft();
            }

            @Override
            public OverallReportDraft repair(StockResearchSnapshot ignored,
                    OverallReportDraft draft, List<String> issues) {
                repairs.incrementAndGet();
                throw new AssertionError("非阻断校验不得触发模型修复");
            }

            @Override
            public String modelName() {
                return "deepseek-chat";
            }
        };

        OverallReportResponse response = service(generator).generate("600519");

        assertThat(repairs).hasValue(0);
        assertThat(response.status()).isEqualTo(OverallReportStatus.MODEL_ASSISTED);
        assertThat(response.report()).isNotNull();
        assertThat(response.report().overallConclusion()).isEqualTo("建议买入");
        assertThat(response.diagnostic()).isNotNull();
        assertThat(response.diagnostic().modelName()).isEqualTo("deepseek-chat");
        assertThat(response.diagnostic().errorCode())
                .isEqualTo("MODEL_NARRATIVE_VALIDATION_WARNING");
        assertThat(response.diagnostic().validationIssues())
                .contains("TRADE_INSTRUCTION", "INVALID_DISCLAIMER");
    }

    @Test
    void modelExceptionReturnsLocalFailureDiagnosticWithoutReport() {
        OverallReportGenerator generator = new OverallReportGenerator() {
            @Override
            public OverallReportDraft generate(StockResearchSnapshot ignored) {
                throw new IllegalStateException("model request failed");
            }

            @Override
            public String modelName() {
                return "deepseek-chat";
            }
        };

        OverallReportResponse response = service(generator).generate("600519");

        assertThat(response.status()).isEqualTo(OverallReportStatus.MODEL_FAILED);
        assertThat(response.report()).isNull();
        assertThat(response.diagnostic()).isNotNull();
        assertThat(response.diagnostic().errorCode()).isEqualTo("MODEL_REQUEST_FAILED");
        assertThat(response.diagnostic().modelName()).isEqualTo("deepseek-chat");
    }

    private OverallReportService service(OverallReportGenerator generator) {
        return new OverallReportService(generator, new StockAgentTools(id -> snapshot),
                new OverallReportValidator(), new ModelFailureClassifier());
    }

    private static OverallReportDraft validDraft() {
        return new OverallReportDraft(
                "当前快照支持中性观察",
                "行情与K线不可用，结论受数据完整性限制",
                "公司与基本面数据不可用",
                "技术与资金数据不可用",
                "估值与行业数据不可用",
                "事件与情绪数据不可用",
                List.of("暂无可核验的积极证据"),
                List.of("暂无可核验的反向证据"),
                List.of("数据缺失限制后续判断"),
                Map.of("stronger", "若后续数据改善并保持稳定", "neutral", "若数据仍不完整",
                        "weaker", "若缺失数据揭示不利变化"),
                List.of("quote unavailable", "bars unavailable"),
                List.of(), OverallResearchReport.REQUIRED_DISCLAIMER);
    }

    private static OverallReportDraft invalidDraft() {
        return new OverallReportDraft(
                "建议买入",
                "数据质量未知",
                "基本面未知",
                "技术与资金未知",
                "估值与行业未知",
                "事件与情绪未知",
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), "免责声明缺失");
    }
}
