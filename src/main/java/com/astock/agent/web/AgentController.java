package com.astock.agent.web;

import com.astock.agent.agent.AgentResearchReport;
import com.astock.agent.agent.AgentStatusService;
import com.astock.agent.agent.StockAnalysisAgent;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentStatusService statusService;
    private final StockAnalysisAgent agent;

    public AgentController(AgentStatusService statusService, StockAnalysisAgent agent) {
        this.statusService = statusService;
        this.agent = agent;
    }

    @GetMapping("/status")
    public Map<String, String> status() {
        return Map.of("status", statusService.status().name(), "details", statusService.details());
    }

    @PostMapping("/analyze")
    public AgentResearchReport analyze(@RequestBody AnalyzeRequest request) {
        if (agent == null) {
            throw new IllegalStateException("Agent is unavailable");
        }
        return agent.analyze(request.code());
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        validateMessage(request.message());
        AgentResearchReport report = analyze(new AnalyzeRequest(request.code()));
        return Map.of(
                "question", request.message(),
                "report", report,
                "note", "首版 follow-up 会在当前股票快照范围内重新综合，不访问任意 URL。" );
    }

    private static void validateMessage(String message) {
        if (message == null || message.isBlank()
                || message.codePointCount(0, message.length()) > 2_000) {
            throw new IllegalArgumentException("Chat message must contain 1 to 2000 Unicode code points");
        }
    }

    public record AnalyzeRequest(String code) {
    }

    public record ChatRequest(String code, String message, String conversationId) {
    }
}
