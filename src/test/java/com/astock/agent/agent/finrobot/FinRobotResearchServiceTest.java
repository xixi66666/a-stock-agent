package com.astock.agent.agent.finrobot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.model.ModelNotAvailableException;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.agent.overall.OverallReportDraft;
import com.astock.agent.agent.overall.OverallReportGenerator;
import com.astock.agent.agent.overall.OverallResearchReport;
import com.astock.agent.agent.overall.SupplementalResearchEvidence;
import com.astock.agent.agent.report.InstitutionalReportComposer;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.analysis.institutional.ResearchJudgementEngine;
import com.astock.agent.marketdata.model.SecurityId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class FinRobotResearchServiceTest {

    private final StockResearchSnapshot snapshot =
            StockResearchSnapshot.empty(SecurityId.parse("600519"));

    @Test
    void missingModelReturnsDeterministicFinRobotReport() {
        FinRobotResearchResponse response = service(
                new NamedChatClientRegistry(Map.of(), Map.of()),
                named -> validGenerator("unused", new AtomicInteger()))
                .generate("600519", null);

        assertThat(response.status()).isEqualTo(FinRobotResearchStatus.MODEL_NOT_CONFIGURED);
        assertThat(response.report()).isNotNull();
        assertThat(response.report().generationMode()).isEqualTo(FinRobotGenerationMode.REPORT_UNAVAILABLE);
        assertThat(response.report().pipelineVersion()).isEqualTo(FinRobotResearchService.PIPELINE_VERSION);
        assertThat(response.report().disclaimer()).isEqualTo(FinRobotResearchReport.REQUIRED_DISCLAIMER);
    }

    @Test
    void selectedModelGeneratesTheUnifiedFinRobotReport() {
        AtomicInteger calls = new AtomicInteger();
        FinRobotResearchResponse response = service(
                registry("mimo"),
                named -> validGenerator(named.modelName(), calls))
                .generate("600519", "mimo");

        assertThat(response.status()).isEqualTo(FinRobotResearchStatus.MODEL_ASSISTED);
        assertThat(response.report()).isNotNull();
        assertThat(response.report().modelName()).isEqualTo("mimo-v2.5-pro");
        assertThat(response.report().generationMode()).isEqualTo(FinRobotGenerationMode.MODEL_ASSISTED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void modelContentIsReturnedWithoutValidationOrRepair() {
        AtomicInteger repairs = new AtomicInteger();
        String conclusion = "研究情景参考值 123456.789，按模型原文输出";
        OverallReportDraft draft = new OverallReportDraft(
                conclusion, "", "", "", "", "",
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), "模型原文");
        FinRobotResearchResponse response = service(registry("mimo"),
                named -> new OverallReportGenerator() {
                    @Override
                    public OverallReportDraft generate(StockResearchSnapshot ignored) {
                        return draft;
                    }

                    @Override
                    public OverallReportDraft repair(StockResearchSnapshot ignored,
                            OverallReportDraft previous, List<String> issues) {
                        repairs.incrementAndGet();
                        return previous;
                    }

                    @Override
                    public String modelName() {
                        return named.modelName();
                    }
                }).generate("600519", "mimo");

        assertThat(response.status()).isEqualTo(FinRobotResearchStatus.MODEL_ASSISTED);
        assertThat(response.report().tagline()).isEqualTo(conclusion);
        assertThat(response.diagnostic()).isNull();
        assertThat(repairs).hasValue(0);
    }

    @Test
    void latestUziEvidenceIsPassedToFinRobotGenerator() throws Exception {
        SupplementalResearchEvidence evidence = new SupplementalResearchEvidence(
                "UZI",
                new ObjectMapper().readTree("{\"companyProfile\":{\"business\":\"test\"}}"),
                List.of(),
                List.of("UZI test limitation"));
        AtomicReference<SupplementalResearchEvidence> received = new AtomicReference<>();
        FinRobotResearchService service = new FinRobotResearchService(
                registry("mimo"),
                new StockAgentTools(id -> snapshot),
                new ResearchJudgementEngine(),
                new InstitutionalReportComposer(),
                new ModelFailureClassifier(),
                named -> new OverallReportGenerator() {
                    @Override
                    public OverallReportDraft generate(StockResearchSnapshot ignored) {
                        return validDraft();
                    }

                    @Override
                    public OverallReportDraft generate(StockResearchSnapshot ignored,
                            SupplementalResearchEvidence actual) {
                        received.set(actual);
                        return validDraft();
                    }

                    @Override
                    public String modelName() {
                        return named.modelName();
                    }
                },
                ignored -> java.util.Optional.of(evidence));

        FinRobotResearchResponse response = service.generate("600519", "mimo");

        assertThat(response.status()).isEqualTo(FinRobotResearchStatus.MODEL_ASSISTED);
        assertThat(received.get()).isSameAs(evidence);
        assertThat(received.get().facts().path("companyProfile").path("business").asText())
                .isEqualTo("test");
    }

    @Test
    void unknownExplicitModelIsRejectedBeforeSnapshotAccess() {
        AtomicInteger fetches = new AtomicInteger();
        StockAgentTools tools = new StockAgentTools(id -> {
            fetches.incrementAndGet();
            return snapshot;
        });
        FinRobotResearchService service = new FinRobotResearchService(
                registry("primary"), tools, new ResearchJudgementEngine(),
                new InstitutionalReportComposer(),
                new ModelFailureClassifier(), named -> validGenerator(named.modelName(), new AtomicInteger()));

        assertThatThrownBy(() -> service.generate("600519", "missing"))
                .isInstanceOf(ModelNotAvailableException.class);
        assertThat(fetches).hasValue(0);
    }

    private FinRobotResearchService service(
            NamedChatClientRegistry registry,
            FinRobotResearchService.GeneratorFactory factory) {
        return new FinRobotResearchService(
                registry,
                new StockAgentTools(id -> snapshot),
                new ResearchJudgementEngine(),
                new InstitutionalReportComposer(),
                new ModelFailureClassifier(),
                factory,
                ignored -> java.util.Optional.empty());
    }

    private NamedChatClientRegistry registry(String defaultId) {
        return new NamedChatClientRegistry(
                Map.of(
                        "primary", new NamedChatClientRegistry.NamedModel(mock(ChatClient.class), "gpt-5"),
                        "mimo", new NamedChatClientRegistry.NamedModel(mock(ChatClient.class), "mimo-v2.5-pro")),
                Map.of(FinRobotResearchService.ROLE, defaultId));
    }

    private OverallReportGenerator validGenerator(String modelName, AtomicInteger calls) {
        return new OverallReportGenerator() {
            @Override
            public OverallReportDraft generate(StockResearchSnapshot ignored) {
                calls.incrementAndGet();
                return validDraft();
            }

            @Override
            public String modelName() {
                return modelName;
            }
        };
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
                List.of("quote unavailable", "bars unavailable"), List.of(),
                OverallResearchReport.REQUIRED_DISCLAIMER);
    }
}
