package com.astock.agent.agent.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.agent.report.GenerationMode;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.analysis.FinancialDataUnavailableException;
import com.astock.agent.analysis.ResearchGateway;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.Exchange;
import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class FinancialReportServiceTest {

    private static final Provenance SOURCE = new Provenance(
            "fixture", URI.create("https://example.com"), null, Instant.now(), false, null);

    @Test
    void missingModelFallsBackDeterministically() {
        FinancialReportService service = new FinancialReportService(
                gateway(history(12)),
                tools(),
                new NamedChatClientRegistry(Map.of(), Map.of("financial-report", "missing")),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> {
                    throw new AssertionError("无模型时不得构造生成器");
                });

        FinancialReportAnalysis analysis = service.generate("600519");

        assertThat(analysis.generationMode()).isEqualTo(GenerationMode.DETERMINISTIC_FALLBACK);
        assertThat(analysis.qualityScore().sufficientData()).isTrue();
        assertThat(analysis.narrative().tierInterpretation())
                .contains("总体判断")
                .contains(analysis.qualityScore().tier());
    }

    @Test
    void validModelDraftReturnsModelAssisted() {
        FinancialReportService service = new FinancialReportService(
                gateway(history(12)),
                tools(),
                new NamedChatClientRegistry(
                        Map.of("deepseek", new NamedChatClientRegistry.NamedModel(
                                mock(ChatClient.class), "deepseek-chat")),
                        Map.of("financial-report", "deepseek")),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> new FinancialReportGenerator() {
                    @Override
                    public FinancialNarrativeDraft generate(FinancialEvidencePackage pack) {
                        assertThat(pack.provenance()).isEqualTo(SOURCE);
                        return new FinancialNarrativeDraft(
                                "F-Score 为 " + pack.qualityScore().total() + " 分，档位 "
                                        + pack.qualityScore().tier() + "。",
                                "盈利信号通过。", "营收趋势上升。", "注意数据完整性限制。",
                                FinancialDeterministicComposer.REQUIRED_DISCLAIMER);
                    }

                    @Override
                    public FinancialNarrativeDraft repair(FinancialEvidencePackage pack,
                            FinancialNarrativeDraft draft, List<String> issues) {
                        throw new AssertionError("合法草稿不得触发修复");
                    }

                    @Override
                    public String modelName() {
                        return "deepseek-chat";
                    }
                });

        FinancialReportAnalysis analysis = service.generate("600519");

        assertThat(analysis.generationMode()).isEqualTo(GenerationMode.MODEL_ASSISTED);
        assertThat(analysis.diagnostic()).isNull();
    }

    @Test
    void returnsLatestFinancialPeriodWithOriginalStatusAndProvenance() {
        FinancialReportService service = new FinancialReportService(
                gateway(history(12)),
                tools(),
                new NamedChatClientRegistry(Map.of(), Map.of()),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> {
                    throw new AssertionError("无模型时不得构造生成器");
                });

        FinancialReportAnalysis analysis = service.generate("600519");

        assertThat(analysis.latestPeriod().status()).isEqualTo(com.astock.agent.marketdata.model.SectionStatus.HEALTHY);
        assertThat(analysis.latestPeriod().payload()).isPresent();
        assertThat(analysis.latestPeriod().payload().orElseThrow().reportPeriod())
                .isEqualTo(LocalDate.of(2025, 12, 30));
        assertThat(analysis.latestPeriod().provenance()).contains(SOURCE);
    }

    @Test
    void invalidDraftIsRepairedOnceThenAccepted() {
        AtomicInteger repairs = new AtomicInteger();
        FinancialReportService service = new FinancialReportService(
                gateway(history(12)),
                tools(),
                new NamedChatClientRegistry(
                        Map.of("deepseek", new NamedChatClientRegistry.NamedModel(
                                mock(ChatClient.class), "deepseek-chat")),
                        Map.of("financial-report", "deepseek")),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> new FinancialReportGenerator() {
                    @Override
                    public FinancialNarrativeDraft generate(FinancialEvidencePackage pack) {
                        return new FinancialNarrativeDraft(
                                "F-Score 为 " + pack.qualityScore().total() + " 分，档位 "
                                        + pack.qualityScore().tier() + "。",
                                "建议买入。", "营收趋势上升。", "注意限制。", "错误免责");
                    }

                    @Override
                    public FinancialNarrativeDraft repair(FinancialEvidencePackage pack,
                            FinancialNarrativeDraft draft, List<String> issues) {
                        repairs.incrementAndGet();
                        return new FinancialNarrativeDraft(
                                "F-Score 为 " + pack.qualityScore().total() + " 分，档位 "
                                        + pack.qualityScore().tier() + "。",
                                "盈利信号通过。", "营收趋势上升。", "注意数据完整性限制。",
                                FinancialDeterministicComposer.REQUIRED_DISCLAIMER);
                    }

                    @Override
                    public String modelName() {
                        return "deepseek-chat";
                    }
                });

        FinancialReportAnalysis analysis = service.generate("600519");

        assertThat(repairs).hasValue(1);
        assertThat(analysis.generationMode()).isEqualTo(GenerationMode.MODEL_ASSISTED);
    }

    @Test
    void modelExceptionReturnsDeterministicFallbackWithDiagnostic() {
        FinancialReportService service = new FinancialReportService(
                gateway(history(12)),
                tools(),
                new NamedChatClientRegistry(
                        Map.of("deepseek", new NamedChatClientRegistry.NamedModel(
                                mock(ChatClient.class), "deepseek-chat")),
                        Map.of("financial-report", "deepseek")),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> new FinancialReportGenerator() {
                    @Override
                    public FinancialNarrativeDraft generate(FinancialEvidencePackage pack) {
                        throw new IllegalStateException("model request failed");
                    }

                    @Override
                    public FinancialNarrativeDraft repair(FinancialEvidencePackage pack,
                            FinancialNarrativeDraft draft, List<String> issues) {
                        throw new AssertionError("异常时不得触发修复");
                    }

                    @Override
                    public String modelName() {
                        return "deepseek-chat";
                    }
                });

        FinancialReportAnalysis analysis = service.generate("600519");

        assertThat(analysis.generationMode()).isEqualTo(GenerationMode.DETERMINISTIC_FALLBACK);
        assertThat(analysis.diagnostic()).isNotNull();
        assertThat(analysis.diagnostic().errorCode()).isEqualTo("MODEL_REQUEST_FAILED");
    }

    @Test
    void unavailableHistoryThrowsFinancialDataUnavailable() {
        FinancialReportService service = new FinancialReportService(
                unavailableGateway(),
                tools(),
                new NamedChatClientRegistry(Map.of(), Map.of()),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> {
                    throw new AssertionError("无模型时不得构造生成器");
                });

        assertThatThrownBy(() -> service.generate("600519"))
                .isInstanceOf(FinancialDataUnavailableException.class);
    }

    @Test
    void insufficientPeriodsStillReturnsResponse() {
        FinancialReportService service = new FinancialReportService(
                gateway(history(3)),
                tools(),
                new NamedChatClientRegistry(Map.of(), Map.of()),
                Caffeine.newBuilder().build(),
                new FinancialReportValidator(),
                new ModelFailureClassifier(),
                named -> {
                    throw new AssertionError("无模型时不得构造生成器");
                });

        FinancialReportAnalysis analysis = service.generate("600519");

        assertThat(analysis.generationMode()).isEqualTo(GenerationMode.DETERMINISTIC_FALLBACK);
        assertThat(analysis.qualityScore().sufficientData()).isFalse();
    }

    private static StockAgentTools tools() {
        return new StockAgentTools(id -> StockResearchSnapshot.empty(SecurityId.parse("600519")));
    }

    private static ResearchGateway unavailableGateway() {
        return new ResearchGateway() {
            @Override
            public DataSection<FinancialStatementHistory> financialHistory(SecurityId security) {
                return DataSection.unavailable("Sina statements failed");
            }

            @Override public DataSection<Quote> quote(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<List<DailyBar>> bars(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<List<DailyBar>> crossCheckBars(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> sectors(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<IndustryValuationData> industryValuation(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> fundFlow(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> capital(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> fundamentals(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> research(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> news(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> announcements(SecurityId security) {
                return DataSection.unavailable("stub");
            }
        };
    }

    private static ResearchGateway gateway(FinancialStatementHistory history) {
        return new ResearchGateway() {
            @Override
            public DataSection<FinancialStatementHistory> financialHistory(SecurityId security) {
                return DataSection.healthy(history, SOURCE);
            }

            @Override public DataSection<Quote> quote(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<List<DailyBar>> bars(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<List<DailyBar>> crossCheckBars(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> sectors(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<IndustryValuationData> industryValuation(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> fundFlow(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> capital(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> fundamentals(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> research(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> news(SecurityId security) {
                return DataSection.unavailable("stub");
            }
            @Override public DataSection<?> announcements(SecurityId security) {
                return DataSection.unavailable("stub");
            }
        };
    }

    private static FinancialStatementHistory history(int size) {
        List<FinancialPeriodStatement> periods = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            periods.add(new FinancialPeriodStatement(
                    LocalDate.of(2023 + i / 4, (i % 4 + 1) * 3, 30),
                    BigDecimal.valueOf(1000 + i * 50L),
                    BigDecimal.valueOf(350 + i * 10L),
                    BigDecimal.valueOf(120 + i * 8L),
                    BigDecimal.valueOf(110 + i * 8L),
                    BigDecimal.valueOf(300 + i * 20L),
                    BigDecimal.valueOf(5000 + i * 200L),
                    BigDecimal.valueOf(2000 + i * 60L),
                    BigDecimal.valueOf(1500 + i * 40L),
                    BigDecimal.valueOf(800 + i * 30L),
                    BigDecimal.valueOf(500L),
                    BigDecimal.valueOf(2500 + i * 100L)));
        }
        return new FinancialStatementHistory(new SecurityId("600519", Exchange.SHANGHAI), periods);
    }
}
