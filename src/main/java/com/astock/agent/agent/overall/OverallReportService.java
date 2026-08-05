package com.astock.agent.agent.overall;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.model.ModelNotAvailableException;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.agent.report.ModelDiagnostic;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.analysis.StockResearchSnapshot;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

/** 编排总体报告生成、确定性校验、一次修复和局部诊断。 */
public final class OverallReportService {

    private static final String OVERALL_REPORT_ROLE = "overall-report";

    @FunctionalInterface
    interface GeneratorFactory {
        OverallReportGenerator create(NamedChatClientRegistry.NamedModel model);
    }

    private final NamedChatClientRegistry registry;
    private final StockAgentTools tools;
    private final OverallReportValidator validator;
    private final ModelFailureClassifier classifier;
    private final GeneratorFactory generatorFactory;

    public OverallReportService(NamedChatClientRegistry registry,
            StockAgentTools tools,
            OverallReportValidator validator,
            ModelFailureClassifier classifier) {
        this(registry, tools, validator, classifier,
                model -> new SpringAiOverallReportGenerator(model.client(), model.modelName()));
    }

    OverallReportService(NamedChatClientRegistry registry,
            StockAgentTools tools,
            OverallReportValidator validator,
            ModelFailureClassifier classifier,
            GeneratorFactory generatorFactory) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.tools = Objects.requireNonNull(tools, "tools is required");
        this.validator = Objects.requireNonNull(validator, "validator is required");
        this.classifier = Objects.requireNonNull(classifier, "classifier is required");
        this.generatorFactory = Objects.requireNonNull(generatorFactory, "generatorFactory is required");
    }

    public java.util.List<NamedChatClientRegistry.ModelReference> availableModels() {
        return registry.availableModels(OVERALL_REPORT_ROLE);
    }

    public OverallReportResponse generate(String code) {
        return generate(code, (String) null);
    }

    public OverallReportResponse generate(String code, String modelId) {
        boolean explicitSelection = modelId != null && !modelId.isBlank();
        java.util.Optional<NamedChatClientRegistry.NamedModel> selected = explicitSelection
                ? registry.byId(modelId)
                : registry.forRole(OVERALL_REPORT_ROLE);
        if (selected.isEmpty()) {
            if (explicitSelection) {
                throw new ModelNotAvailableException();
            }
            return new OverallReportResponse(
                    OverallReportStatus.MODEL_NOT_CONFIGURED,
                    null,
                    null,
                    "总体报告模型未配置");
        }
        OverallReportGenerator generator = generatorFactory.create(selected.orElseThrow());
        return generateWith(code, generator);
    }

    private OverallReportResponse generateWith(String code, OverallReportGenerator generator) {
        long started = System.nanoTime();
        String traceId = "overall-" + UUID.randomUUID();
        String modelName = generator.modelName();
        try {
            StockResearchSnapshot snapshot = tools.getResearchSnapshot(code);
            OverallReportDraft draft = generator.generate(snapshot);
            OverallReportValidator.Validation validation = validator.validate(draft, snapshot);
            ModelDiagnostic diagnostic = validation.issues().isEmpty()
                    ? null
                    : classifier.validationWarning(
                            validation.issues(), modelName, elapsedMillis(started), traceId);

            OverallResearchReport report = OverallResearchReport.fromSnapshot(
                    draft,
                    modelName,
                    snapshot,
                    Instant.now(),
                    SpringAiOverallReportGenerator.PROMPT_VERSION);
            return new OverallReportResponse(
                    OverallReportStatus.MODEL_ASSISTED,
                    report,
                    diagnostic,
                    validation.issues().isEmpty() ? "总体报告已生成" : "总体报告已生成，存在校验警告");
        } catch (Exception failure) {
            ModelDiagnostic diagnostic = classifier.classify(
                    failure, modelName, elapsedMillis(started), traceId);
            return new OverallReportResponse(
                    OverallReportStatus.MODEL_FAILED,
                    null,
                    diagnostic,
                    "总体报告生成失败");
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
