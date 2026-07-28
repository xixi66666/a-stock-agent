package com.astock.agent.web;

import com.astock.agent.agent.learning.LearningAgentFacade;
import com.astock.agent.agent.learning.LearningAgentResponse;
import com.astock.agent.marketdata.model.SecurityId;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/learning")
@ConditionalOnBean(LearningAgentFacade.class)
public class LearningAgentController {

    private final LearningAgentFacade facade;

    public LearningAgentController(LearningAgentFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        try {
            SecurityId.parse(request.code());
            if (request.message() == null || request.message().isBlank()
                    || request.message().codePointCount(0, request.message().length()) > 2_000
                    || request.conversationId() == null || request.conversationId().isBlank()) {
                throw new IllegalArgumentException("Learning request fields are invalid");
            }
            if (facade == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "type", "about:blank", "title", "Learning agent disabled", "status", 404));
            }
            LearningAgentResponse response = facade.chat(request.code(), request.message(), request.conversationId());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "type", "about:blank",
                    "title", "Invalid learning agent request",
                    "status", 400,
                    "detail", exception.getMessage()));
        }
    }

    public record ChatRequest(String code, String message, String conversationId) {
    }
}
