package com.astock.agent.agent.finrobot;

import com.astock.agent.agent.model.ModelNotAvailableException;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.agent.overall.OverallReportDraft;
import com.astock.agent.agent.overall.OverallReportGenerator;
import com.astock.agent.agent.overall.OverallResearchReport;
import com.astock.agent.agent.overall.OverallSourceReference;
import com.astock.agent.agent.overall.SupplementalResearchEvidence;
import com.astock.agent.agent.report.InstitutionalReportComposer;
import com.astock.agent.agent.report.InstitutionalResearchReport;
import com.astock.agent.agent.report.ModelDiagnostic;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.analysis.institutional.DeterministicAssessment;
import com.astock.agent.analysis.institutional.ResearchJudgementEngine;
import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.uzi.UziResearchBundle;
import com.astock.agent.agent.uzi.UziResearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * FinRobot equity research 流水线。
 *
 * <p>它对应官方模块的边界：先读取规范化数据并完成确定性分析，再由模型生成报告角色文本，
 * 模型输出直接映射为报告，不执行内容校验或修复。模型失败时只回退本次报告，不影响行情接口。</p>
 */
public final class FinRobotResearchService {

    public static final String ROLE = "finrobot-research";
    public static final String PIPELINE_VERSION = "finrobot-equity-v1";

    @FunctionalInterface
    interface GeneratorFactory {
        OverallReportGenerator create(NamedChatClientRegistry.NamedModel model);
    }

    private final NamedChatClientRegistry registry;
    private final StockAgentTools tools;
    private final ResearchJudgementEngine judgementEngine;
    private final InstitutionalReportComposer composer;
    private final ModelFailureClassifier classifier;
    private final GeneratorFactory generatorFactory;
    private final UziEvidenceProvider uziEvidenceProvider;

    public FinRobotResearchService(
            NamedChatClientRegistry registry,
            StockAgentTools tools,
            ResearchJudgementEngine judgementEngine,
            InstitutionalReportComposer composer,
            ModelFailureClassifier classifier) {
        this(registry, tools, judgementEngine, composer, classifier,
                model -> new com.astock.agent.agent.overall.SpringAiOverallReportGenerator(
                        model.client(), model.modelName()),
                ignored -> Optional.empty());
    }

    public FinRobotResearchService(
            NamedChatClientRegistry registry,
            StockAgentTools tools,
            ResearchJudgementEngine judgementEngine,
            InstitutionalReportComposer composer,
            ModelFailureClassifier classifier,
            UziResearchService uziResearchService) {
        this(registry, tools, judgementEngine, composer, classifier,
                model -> new com.astock.agent.agent.overall.SpringAiOverallReportGenerator(
                        model.client(), model.modelName()),
                uziEvidenceProvider(uziResearchService));
    }

    FinRobotResearchService(
            NamedChatClientRegistry registry,
            StockAgentTools tools,
            ResearchJudgementEngine judgementEngine,
            InstitutionalReportComposer composer,
            ModelFailureClassifier classifier,
            GeneratorFactory generatorFactory) {
        this(registry, tools, judgementEngine, composer, classifier,
                generatorFactory, ignored -> Optional.empty());
    }

    FinRobotResearchService(
            NamedChatClientRegistry registry,
            StockAgentTools tools,
            ResearchJudgementEngine judgementEngine,
            InstitutionalReportComposer composer,
            ModelFailureClassifier classifier,
            GeneratorFactory generatorFactory,
            UziEvidenceProvider uziEvidenceProvider) {
        this.registry = registry == null ? new NamedChatClientRegistry(null, null) : registry;
        this.tools = Objects.requireNonNull(tools, "tools is required");
        this.judgementEngine = Objects.requireNonNull(judgementEngine, "judgementEngine is required");
        this.composer = Objects.requireNonNull(composer, "composer is required");
        this.classifier = Objects.requireNonNull(classifier, "classifier is required");
        this.generatorFactory = Objects.requireNonNull(generatorFactory, "generatorFactory is required");
        this.uziEvidenceProvider = Objects.requireNonNull(uziEvidenceProvider,
                "uziEvidenceProvider is required");
    }

    public List<NamedChatClientRegistry.ModelReference> availableModels() {
        return registry.availableModels(ROLE);
    }

    /** 生成一份完整的 FinRobot 单证券研究报告。 */
    public FinRobotResearchResponse generate(String code, String modelId) {
        com.astock.agent.marketdata.model.SecurityId.parse(code);
        Optional<NamedChatClientRegistry.NamedModel> selected = selectModel(modelId);
        StockResearchSnapshot snapshot = tools.getResearchSnapshot(code);

        if (selected.isEmpty()) {
            return fallback(snapshot, FinRobotResearchStatus.MODEL_NOT_CONFIGURED, null,
                    "FinRobot 模型未配置，已返回确定性研究结果");
        }
        SupplementalResearchEvidence evidence = uziEvidenceProvider.latest(code).orElse(null);
        return generateWith(snapshot, selected.orElseThrow(), evidence);
    }

    private Optional<NamedChatClientRegistry.NamedModel> selectModel(String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            Optional<NamedChatClientRegistry.NamedModel> explicit = registry.byId(modelId);
            if (explicit.isEmpty()) throw new ModelNotAvailableException();
            return explicit;
        }
        return registry.forRole(ROLE);
    }

    private FinRobotResearchResponse generateWith(
            StockResearchSnapshot snapshot,
            NamedChatClientRegistry.NamedModel model,
            SupplementalResearchEvidence evidence) {
        long started = System.nanoTime();
        String traceId = "finrobot-" + UUID.randomUUID();
        String modelName = model.modelName();
        try {
            OverallReportGenerator generator = generatorFactory.create(model);
            OverallReportDraft draft = generator.generate(snapshot, evidence);
            OverallResearchReport overall = OverallResearchReport.fromSnapshot(
                    draft, modelName, snapshot, java.time.Instant.now(),
                    "finrobot-equity-narrative-v1");
            return new FinRobotResearchResponse(
                    FinRobotResearchStatus.MODEL_ASSISTED,
                    FinRobotReportMapper.fromOverall(overall, snapshot, FinRobotGenerationMode.MODEL_ASSISTED),
                    null,
                    "FinRobot 投研报告已生成");
        } catch (Exception failure) {
            ModelDiagnostic diagnostic = classifier.classify(
                    failure, modelName, elapsedMillis(started), traceId);
            return fallback(snapshot, FinRobotResearchStatus.MODEL_FAILED, diagnostic,
                    "FinRobot 模型生成失败，已返回确定性研究结果");
        }
    }

    private FinRobotResearchResponse fallback(
            StockResearchSnapshot snapshot,
            FinRobotResearchStatus status,
            ModelDiagnostic diagnostic,
            String message) {
        DeterministicAssessment assessment = judgementEngine.assess(snapshot);
        InstitutionalResearchReport report = composer.fallbackWithDiagnostic(snapshot, assessment, diagnostic);
        FinRobotGenerationMode mode = report.generationMode()
                == com.astock.agent.agent.report.GenerationMode.REPORT_UNAVAILABLE
                        ? FinRobotGenerationMode.REPORT_UNAVAILABLE
                        : FinRobotGenerationMode.DETERMINISTIC_FALLBACK;
        return new FinRobotResearchResponse(
                status,
                FinRobotReportMapper.fromInstitutional(report, snapshot, mode),
                diagnostic,
                message);
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static UziEvidenceProvider uziEvidenceProvider(UziResearchService service) {
        if (service == null) return ignored -> Optional.empty();
        return code -> {
            try {
                UziResearchService.Task task = service.latest(code);
                if (!"COMPLETED".equals(task.status()) || task.bundle() == null) {
                    return Optional.empty();
                }
                return Optional.of(toEvidence(task.bundle()));
            } catch (RuntimeException unavailable) {
                // UZI 是 FinRobot 的增强证据；不可用时保留原有快照研究能力。
                return Optional.empty();
            }
        };
    }

    private static SupplementalResearchEvidence toEvidence(UziResearchBundle bundle) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode facts = mapper.createObjectNode();
        facts.put("schema", bundle.schema());
        facts.put("ticker", bundle.ticker());
        if (bundle.generatedAt() != null) facts.put("generatedAt", bundle.generatedAt());
        facts.set("rawData", nodeOrNull(bundle.rawData()));
        facts.set("dimensions", nodeOrNull(bundle.dimensions()));
        facts.set("panel", nodeOrNull(bundle.panel()));
        facts.set("synthesis", nodeOrNull(bundle.synthesis()));
        facts.set("structured", mapper.valueToTree(bundle.structured()));
        facts.set("dataGaps", mapper.valueToTree(bundle.dataGaps()));

        List<OverallSourceReference> sources = bundle.sources().stream()
                .filter(source -> source != null && source.provider() != null
                        && !source.provider().isBlank())
                .map(source -> new OverallSourceReference(
                        "uzi:" + safeDimension(source.dimension()), source.provider(), source.url(),
                        parseInstant(source.observedAt())))
                .toList();
        return new SupplementalResearchEvidence("UZI", facts, sources, bundle.dataGaps());
    }

    private static com.fasterxml.jackson.databind.JsonNode nodeOrNull(
            com.fasterxml.jackson.databind.JsonNode node) {
        return node == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : node;
    }

    private static String safeDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) return "unknown";
        return dimension.trim().replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private static java.time.Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
