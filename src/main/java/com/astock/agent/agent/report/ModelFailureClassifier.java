package com.astock.agent.agent.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 将模型异常转换为稳定错误码，并删除可能泄露凭据的值。 */
public final class ModelFailureClassifier {
    private static final Pattern AUTH = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*(?:bearer|basic)?\\s*)[^\\s,;]+(?:\\s+[^\\s,;]+)?");
    private static final Pattern SENSITIVE_QUERY = Pattern.compile(
            "(?i)([?&](?:key|api[-_]?key|token|secret|authorization)=)[^&#\\s]+");
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)((?:key|api[-_]?key|token|secret|authorization|cookie)\\s*[:=]\\s*)[^\\s,;]+");
    private static final Pattern HTTP_STATUS = Pattern.compile("(?<!\\d)[45]\\d{2}(?!\\d)");

    public ModelDiagnostic classify(Throwable failure, String modelName, long durationMs, String traceId) {
        Throwable root = rootCause(failure);
        Classification classification = classification(root);
        String message = sanitize(root.getMessage());
        if (message.isBlank()) message = classification.defaultMessage();
        return new ModelDiagnostic(classification.stage(), classification.code(), root.getClass().getSimpleName(),
                message, List.of(), modelName, durationMs, Instant.now(), traceId);
    }

    public ModelDiagnostic validation(List<String> issues, String modelName, long durationMs, String traceId) {
        List<String> safeIssues = issues == null ? List.of() : List.copyOf(issues);
        String message = safeIssues.isEmpty() ? "模型叙述未通过报告校验"
                : "模型叙述未通过报告校验：" + String.join(",", safeIssues);
        return new ModelDiagnostic(ModelFailureStage.VALIDATION, "MODEL_NARRATIVE_VALIDATION_FAILED",
                "ReportValidationException", message, safeIssues, modelName, durationMs, Instant.now(), traceId);
    }

    public ModelDiagnostic validationWarning(List<String> issues, String modelName, long durationMs, String traceId) {
        List<String> safeIssues = issues == null ? List.of() : List.copyOf(issues);
        String message = safeIssues.isEmpty() ? "模型叙述存在非阻断校验警告"
                : "模型叙述存在非阻断校验警告：" + String.join(",", safeIssues);
        return new ModelDiagnostic(ModelFailureStage.VALIDATION, "MODEL_NARRATIVE_VALIDATION_WARNING",
                "ReportValidationWarning", message, safeIssues, modelName, durationMs, Instant.now(), traceId);
    }

    public String sanitize(String value) {
        if (value == null) return "";
        String result = AUTH.matcher(value).replaceAll("$1[REDACTED]");
        result = SENSITIVE_QUERY.matcher(result).replaceAll("$1[REDACTED]");
        return SENSITIVE_FIELD.matcher(result).replaceAll("$1[REDACTED]");
    }

    private static Classification classification(Throwable failure) {
        if (failure instanceof HttpTimeoutException || contains(failure, "timeout", "timed out")) {
            return new Classification(ModelFailureStage.REQUEST, "MODEL_TIMEOUT", "模型请求超时");
        }
        if (failure instanceof ConnectException || contains(failure, "connection refused", "connect failed")) {
            return new Classification(ModelFailureStage.REQUEST, "MODEL_CONNECTION_FAILED", "模型连接失败");
        }
        if (failure instanceof JsonProcessingException || contains(failure, "json", "parse", "mapping")) {
            return new Classification(ModelFailureStage.RESPONSE_MAPPING, "MODEL_RESPONSE_PARSE_FAILED", "模型响应解析失败");
        }
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (HTTP_STATUS.matcher(message).find()) {
            return new Classification(ModelFailureStage.REQUEST, "MODEL_HTTP_ERROR", "模型服务返回HTTP错误");
        }
        if (contains(failure, "empty response", "no narrative")) {
            return new Classification(ModelFailureStage.RESPONSE_MAPPING, "MODEL_EMPTY_RESPONSE", "模型返回空响应");
        }
        return new Classification(ModelFailureStage.REQUEST, "MODEL_REQUEST_FAILED", "模型请求失败");
    }

    private static boolean contains(Throwable failure, String... terms) {
        String value = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private static Throwable rootCause(Throwable failure) {
        if (failure == null) return new IllegalStateException("Unknown model failure");
        Throwable result = failure;
        while (result.getCause() != null && result.getCause() != result) result = result.getCause();
        return result;
    }

    private record Classification(ModelFailureStage stage, String code, String defaultMessage) { }
}
