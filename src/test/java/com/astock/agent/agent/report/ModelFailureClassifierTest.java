package com.astock.agent.agent.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelFailureClassifierTest {
    private final ModelFailureClassifier classifier = new ModelFailureClassifier();

    @Test
    void classifiesTimeoutAndPreservesSafeRootCause() {
        Throwable failure = new RuntimeException("request failed",
                new HttpTimeoutException("timed out after 30s"));

        ModelDiagnostic result = classifier.classify(failure, "gpt-test", 30123, "trace-1");

        assertThat(result.failureStage()).isEqualTo(ModelFailureStage.REQUEST);
        assertThat(result.errorCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(result.exceptionType()).isEqualTo("HttpTimeoutException");
        assertThat(result.message()).contains("30s");
    }

    @Test
    void createsValidationDiagnosticWithExactIssueCodes() {
        ModelDiagnostic result = classifier.validation(List.of("UNSUPPORTED_NUMBER", "UNKNOWN_EVIDENCE"),
                "gpt-test", 80, "trace-2");

        assertThat(result.failureStage()).isEqualTo(ModelFailureStage.VALIDATION);
        assertThat(result.errorCode()).isEqualTo("MODEL_NARRATIVE_VALIDATION_FAILED");
        assertThat(result.validationIssues()).containsExactly("UNSUPPORTED_NUMBER", "UNKNOWN_EVIDENCE");
    }

    @Test
    void redactsCredentialsAndSensitiveQueryValues() {
        RuntimeException failure = new RuntimeException(
                "401 url=https://example.test/v1?token=secret-value Authorization: Bearer abc123");

        ModelDiagnostic result = classifier.classify(failure, "gpt-test", 20, "trace-3");

        assertThat(result.message()).doesNotContain("secret-value", "abc123").contains("[REDACTED]");
        assertThat(result.errorCode()).isEqualTo("MODEL_HTTP_ERROR");
    }
}
