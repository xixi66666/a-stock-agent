package com.astock.agent.agent.overall;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.report.ModelDiagnostic;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.analysis.StockResearchSnapshot;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

/** 编排总体报告生成、确定性校验、一次修复和局部诊断。 */
public final class OverallReportService {

    private final OverallReportGenerator generator;
    private final StockAgentTools tools;
    private final OverallReportValidator validator;
    private final ModelFailureClassifier classifier;

    public OverallReportService(OverallReportGenerator generator,
            StockAgentTools tools,
            OverallReportValidator validator,
            ModelFailureClassifier classifier) {
        this.generator = generator;
        this.tools = Objects.requireNonNull(tools, "tools is required");
        this.validator = Objects.requireNonNull(validator, "validator is required");
        this.classifier = Objects.requireNonNull(classifier, "classifier is required");
    }

    public OverallReportResponse generate(String code) {
        if (generator == null) {
            return new OverallReportResponse(
                    OverallReportStatus.MODEL_NOT_CONFIGURED,
                    null,
                    null,
                    "总体报告模型未配置");
        }

        long started = System.nanoTime();
        String traceId = "overall-" + UUID.randomUUID();
        String modelName = generator.modelName();
        try {
            StockResearchSnapshot snapshot = tools.getResearchSnapshot(code);
            OverallReportDraft draft = generator.generate(snapshot);
            OverallReportValidator.Validation validation = validator.validate(draft, snapshot);

            if (validation.blocking()) {
                // 只允许一次修复，避免模型调用失控或在校验失败时递归重试。
                draft = generator.repair(snapshot, draft, validation.issues());
                validation = validator.validate(draft, snapshot);
            }

            if (validation.blocking()) {
                ModelDiagnostic diagnostic = classifier.validation(
                        validation.issues(), modelName, elapsedMillis(started), traceId);
                return new OverallReportResponse(
                        OverallReportStatus.VALIDATION_FAILED,
                        null,
                        diagnostic,
                        "DeepSeek 总体报告未通过证据校验");
            }

            OverallResearchReport report = OverallResearchReport.from(
                    draft,
                    modelName,
                    snapshot.fetchedAt(),
                    Instant.now(),
                    SpringAiOverallReportGenerator.PROMPT_VERSION);
            return new OverallReportResponse(
                    OverallReportStatus.MODEL_ASSISTED,
                    report,
                    null,
                    "总体报告已生成");
        } catch (Exception failure) {
            ModelDiagnostic diagnostic = classifier.classify(
                    failure, modelName, elapsedMillis(started), traceId);
            return new OverallReportResponse(
                    OverallReportStatus.MODEL_FAILED,
                    null,
                    diagnostic,
                    "DeepSeek 总体报告生成失败");
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
