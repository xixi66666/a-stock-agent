package com.astock.agent.agent.financial;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.agent.report.GenerationMode;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.agent.report.ModelDiagnostic;
import com.astock.agent.analysis.FinancialDataUnavailableException;
import com.astock.agent.analysis.ResearchGateway;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialQualityScorer;
import com.astock.agent.analysis.financial.FinancialTrendCalculator;
import com.astock.agent.analysis.financial.FinancialTrendResult;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SectionStatus;
import com.astock.agent.marketdata.model.SecurityId;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 财报分析编排:历史 -> 评分/趋势 -> 证据包 -> 模型叙事 -> 校验 -> 修复一次 -> 确定性回退。
 */
public final class FinancialReportService {

    static final String ROLE = "financial-report";

    @FunctionalInterface
    interface GeneratorFactory {
        FinancialReportGenerator create(NamedChatClientRegistry.NamedModel model);
    }

    private final ResearchGateway gateway;
    private final StockAgentTools tools;
    private final NamedChatClientRegistry registry;
    private final Cache<SecurityId, DataSection<FinancialStatementHistory>> historyCache;
    private final FinancialReportValidator validator;
    private final ModelFailureClassifier classifier;
    private final GeneratorFactory generatorFactory;
    private final FinancialQualityScorer scorer = new FinancialQualityScorer();
    private final FinancialTrendCalculator trendCalculator = new FinancialTrendCalculator();
    private final FinancialDeterministicComposer composer = new FinancialDeterministicComposer();

    public FinancialReportService(ResearchGateway gateway, StockAgentTools tools,
            NamedChatClientRegistry registry,
            Cache<SecurityId, DataSection<FinancialStatementHistory>> historyCache,
            FinancialReportValidator validator, ModelFailureClassifier classifier) {
        this(gateway, tools, registry, historyCache, validator, classifier,
                model -> new SpringAiFinancialNarrativeGenerator(model.client(), model.modelName()));
    }

    FinancialReportService(ResearchGateway gateway, StockAgentTools tools,
            NamedChatClientRegistry registry,
            Cache<SecurityId, DataSection<FinancialStatementHistory>> historyCache,
            FinancialReportValidator validator, ModelFailureClassifier classifier,
            GeneratorFactory generatorFactory) {
        this.gateway = Objects.requireNonNull(gateway, "gateway is required");
        this.tools = Objects.requireNonNull(tools, "tools is required");
        this.registry = registry == null ? new NamedChatClientRegistry(null, null) : registry;
        this.historyCache = Objects.requireNonNull(historyCache, "historyCache is required");
        this.validator = Objects.requireNonNull(validator, "validator is required");
        this.classifier = Objects.requireNonNull(classifier, "classifier is required");
        this.generatorFactory = Objects.requireNonNull(generatorFactory, "generatorFactory is required");
    }

    public FinancialReportAnalysis generate(String code) {
        SecurityId security = SecurityId.parse(code);
        DataSection<FinancialStatementHistory> section =
                historyCache.get(security, gateway::financialHistory);
        if (section == null || section.status() == SectionStatus.UNAVAILABLE
                || section.payload().isEmpty()) {
            String reason = section == null || section.issues() == null
                    ? "Sina 财报数据不可用" : String.join(";", section.issues());
            throw new FinancialDataUnavailableException(reason);
        }
        FinancialStatementHistory history = section.payload().orElseThrow();
        boolean financialIndustry = detectFinancialIndustry(code);
        FinancialQualityScore score = scorer.score(history, financialIndustry);
        FinancialTrendResult trends = trendCalculator.calculate(history);
        int pass = (int) score.signals().stream()
                .filter(item -> item.status() == FinancialQualityScore.SignalStatus.PASS).count();
        int fail = (int) score.signals().stream()
                .filter(item -> item.status() == FinancialQualityScore.SignalStatus.FAIL).count();
        int unverified = score.signals().size() - pass - fail;
        FinancialEvidencePackage pack = new FinancialEvidencePackage(code, history, score, trends,
                financialIndustry, pass, fail, unverified, score.signals().size());

        String traceId = "financial-" + UUID.randomUUID();
        long started = System.nanoTime();
        Optional<NamedChatClientRegistry.NamedModel> model = registry.forRole(ROLE);
        if (model.isEmpty()) {
            return compose(GenerationMode.DETERMINISTIC_FALLBACK, pack, section, null, null);
        }
        try {
            FinancialReportGenerator generator = generatorFactory.create(model.orElseThrow());
            FinancialNarrativeDraft draft = generator.generate(pack);
            FinancialReportValidator.Validation validation = validator.validate(draft, pack);
            if (validation.blocking()) {
                draft = generator.repair(pack, draft, validation.issues());
                validation = validator.validate(draft, pack);
            }
            if (validation.blocking()) {
                ModelDiagnostic diagnostic = classifier.validation(validation.issues(),
                        generator.modelName(), elapsedMillis(started), traceId);
                return compose(GenerationMode.DETERMINISTIC_FALLBACK, pack, section, null, diagnostic);
            }
            return compose(GenerationMode.MODEL_ASSISTED, pack, section,
                    new FinancialNarrative(draft.tierInterpretation(), draft.signalCommentary(),
                            draft.trendCommentary(), draft.riskNotes()), null);
        } catch (Exception failure) {
            ModelDiagnostic diagnostic = classifier.classify(failure,
                    model.map(NamedChatClientRegistry.NamedModel::modelName)
                            .orElse("configured-financial-model"),
                    elapsedMillis(started), traceId);
            return compose(GenerationMode.DETERMINISTIC_FALLBACK, pack, section, null, diagnostic);
        }
    }

    private boolean detectFinancialIndustry(String code) {
        try {
            StockResearchSnapshot snapshot = tools.getResearchSnapshot(code);
            if (snapshot == null || snapshot.sectors() == null) {
                return false;
            }
            Object payload = snapshot.sectors().payload().orElse(null);
            if (!(payload instanceof List<?> sectors)) {
                return false;
            }
            return sectors.stream().map(Object::toString).anyMatch(name ->
                    List.of("银行", "保险", "证券", "多元金融", "信托")
                            .stream().anyMatch(name::contains));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private FinancialReportAnalysis compose(GenerationMode mode, FinancialEvidencePackage pack,
            DataSection<FinancialStatementHistory> historySection,
            FinancialNarrative narrative, ModelDiagnostic diagnostic) {
        FinancialNarrative text = narrative != null ? narrative : composer.compose(pack);
        String range = pack.history().periodCount() == 0 ? "--"
                : pack.history().periods().get(0).reportPeriod() + " - "
                + pack.history().periods().get(pack.history().periodCount() - 1).reportPeriod();
        DataSection<FinancialPeriodStatement> latestPeriod = latestPeriod(historySection, pack.history());
        return new FinancialReportAnalysis(
                pack.securityCode(), range, pack.history().periodCount(),
                latestPeriod,
                pack.qualityScore(), pack.trends(), text, mode, diagnostic,
                pack.financialIndustry(), Instant.now().toString(),
                FinancialQualityScorer.RULE_VERSION,
                mode == GenerationMode.MODEL_ASSISTED
                        ? SpringAiFinancialNarrativeGenerator.PROMPT_VERSION : "deterministic",
                FinancialReportAnalysis.REQUIRED_DISCLAIMER);
    }

    private static DataSection<FinancialPeriodStatement> latestPeriod(
            DataSection<FinancialStatementHistory> source,
            FinancialStatementHistory history) {
        FinancialPeriodStatement latest = history.periods().get(history.periodCount() - 1);
        return new DataSection<>(source.status(), Optional.of(latest),
                source.provenance(), source.issues());
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
