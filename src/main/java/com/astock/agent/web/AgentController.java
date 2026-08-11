package com.astock.agent.web;

import com.astock.agent.agent.AgentStatusService;
import com.astock.agent.agent.StockAnalysisAgent;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.agent.overall.OverallReportResponse;
import com.astock.agent.agent.overall.OverallReportService;
import com.astock.agent.agent.quant.QuantResearchReportService;
import com.astock.agent.agent.quant.QuantResearchReport;
import com.astock.agent.agent.report.InstitutionalResearchReport;
import com.astock.agent.marketdata.model.SecurityId;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/agent")
/**
 * Agent REST 边界。
 *
 * <p>Controller 只做请求解析、代码校验和服务调用。研究报告、总体报告和学习型聊天
 * 使用不同的 endpoint，避免前端把不同的模型路由和失败语义混在一起。</p>
 */
public class AgentController {

    private final AgentStatusService statusService;
    private final StockAnalysisAgent agent;
    private final OverallReportService overallReports;
    private final QuantResearchReportService quantReports;

    public AgentController(AgentStatusService statusService, StockAnalysisAgent agent) {
        this(statusService, agent, null, null);
    }

    public AgentController(AgentStatusService statusService, StockAnalysisAgent agent,
            OverallReportService overallReports) {
        this(statusService, agent, overallReports, null);
    }

    @Autowired
    public AgentController(AgentStatusService statusService, StockAnalysisAgent agent,
            OverallReportService overallReports, QuantResearchReportService quantReports) {
        this.statusService = statusService;
        this.agent = agent;
        this.overallReports = overallReports;
        this.quantReports = quantReports;
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
        // “生成研究报告”只走 institutional-report 角色；不会使用总体报告下拉框的 modelId。
        if (agent == null) {
            throw new IllegalStateException("Agent is unavailable");
        }
        return agent.analyzeInstitutional(request.code());
    }

    @PostMapping("/quant-report")
    public QuantResearchReport quantReport(@RequestBody AnalyzeRequest request) {
        SecurityId.parse(request.code());
        if (quantReports == null) {
            throw new IllegalStateException("Quantitative report service is unavailable");
        }
        return quantReports.generate(request.code());
    }

    @PostMapping("/overall-report")
    public OverallReportResponse overallReport(@RequestBody OverallReportRequest request) {
        // 总体报告单独校验证券代码，并把可选模型 ID 交给 OverallReportService 路由。
        SecurityId.parse(request.code());
        if (overallReports == null) {
            throw new IllegalStateException("Overall report service is unavailable");
        }
        return overallReports.generate(request.code(), request.modelId());
    }

    @GetMapping("/models")
    public ModelsResponse models(
            @RequestParam(defaultValue = "overall-report") String capability) {
        // 浏览器只获取安全模型目录，不会获取 API Key、Base URL 或连接参数。
        if (!"overall-report".equals(capability)) {
            throw new UnsupportedModelCapabilityException(capability);
        }
        if (overallReports == null) {
            return new ModelsResponse(List.of());
        }
        return new ModelsResponse(overallReports.availableModels());
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

    public record OverallReportRequest(String code, String modelId) {
    }

    public record ModelsResponse(List<NamedChatClientRegistry.ModelReference> models) {
        public ModelsResponse {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    public record ChatRequest(String code, String message, String conversationId) {
    }
}
