package com.astock.agent.web;

import com.astock.agent.agent.AgentStatusService;
import com.astock.agent.agent.StockAnalysisAgent;
import com.astock.agent.agent.overall.OverallReportResponse;
import com.astock.agent.agent.overall.OverallReportService;
import com.astock.agent.agent.report.InstitutionalResearchReport;
import com.astock.agent.marketdata.model.SecurityId;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentStatusService statusService;
    private final StockAnalysisAgent agent;
    private final OverallReportService overallReports;

    public AgentController(AgentStatusService statusService, StockAnalysisAgent agent) {
        this(statusService, agent, null);
    }

    @Autowired
    public AgentController(AgentStatusService statusService, StockAnalysisAgent agent,
            OverallReportService overallReports) {
        this.statusService = statusService;
        this.agent = agent;
        this.overallReports = overallReports;
    }

    @GetMapping("/status")
    public Map<String, String> status() {
        return Map.of(
                "status", statusService.status().name(),
                "details", statusService.details(),
                "institutionalReport", statusService.status("institutional-report").name(),
                "overallReport", statusService.status("overall-report").name());
    }

    @PostMapping("/analyze")
    public InstitutionalResearchReport analyze(@RequestBody AnalyzeRequest request) {
        if (agent == null) {
            throw new IllegalStateException("Agent is unavailable");
        }
        return agent.analyzeInstitutional(request.code());
    }

    @PostMapping("/overall-report")
    public OverallReportResponse overallReport(@RequestBody AnalyzeRequest request) {
        SecurityId.parse(request.code());
        if (overallReports == null) {
            throw new IllegalStateException("Overall report service is unavailable");
        }
        return overallReports.generate(request.code());
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        validateMessage(request.message());
        InstitutionalResearchReport report = analyze(new AnalyzeRequest(request.code()));
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
