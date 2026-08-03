package com.astock.agent.agent.report;

import java.time.Instant;
import java.util.List;

/** 可返回给本地 UI 的脱敏模型故障信息。 */
public record ModelDiagnostic(
        ModelFailureStage failureStage,
        String errorCode,
        String exceptionType,
        String message,
        List<String> validationIssues,
        String modelName,
        long durationMs,
        Instant occurredAt,
        String traceId) {

    public ModelDiagnostic {
        if (failureStage == null || errorCode == null || errorCode.isBlank()
                || message == null || message.isBlank() || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("Diagnostic stage, code, message and traceId are required");
        }
        exceptionType = exceptionType == null ? "" : exceptionType;
        validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
        modelName = modelName == null ? "" : modelName;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
