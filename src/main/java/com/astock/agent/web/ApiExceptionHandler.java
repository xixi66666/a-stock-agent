package com.astock.agent.web;

import com.astock.agent.agent.AgentExecutionException;
import com.astock.agent.agent.model.ModelNotAvailableException;
import com.astock.agent.analysis.ResearchUnavailableException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ModelNotAvailableException.class)
    ResponseEntity<ProblemDetail> modelNotAvailable(ModelNotAvailableException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "MODEL_NOT_AVAILABLE",
                "模型不可用",
                exception.getMessage());
    }

    @ExceptionHandler(UnsupportedModelCapabilityException.class)
    ResponseEntity<ProblemDetail> unsupportedModelCapability(
            UnsupportedModelCapabilityException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_MODEL_CAPABILITY",
                "不支持的模型能力",
                exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalidInput(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_SECURITY_CODE", "请求参数无效", exception.getMessage());
    }

    @ExceptionHandler(ResearchUnavailableException.class)
    ResponseEntity<ProblemDetail> researchUnavailable(ResearchUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "CORE_DATA_UNAVAILABLE", "核心行情不可用", exception.getMessage());
    }

    @ExceptionHandler(AgentExecutionException.class)
    ResponseEntity<ProblemDetail> agentFailure(AgentExecutionException exception) {
        return problem(HttpStatus.BAD_GATEWAY, "AGENT_EXECUTION_FAILED", "Agent 分析失败", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务处理失败", exception.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://a-stock-agent.local/problems/" + code.toLowerCase()));
        problem.setProperty("code", code);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
