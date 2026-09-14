package com.astock.agent.web;

import com.astock.agent.agent.financial.FinancialReportAnalysis;
import com.astock.agent.agent.financial.FinancialReportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 财报分析边界：确定性评分/趋势 + 可选模型叙事，失败时服务内确定性回退。 */
@RestController
@RequestMapping("/api/agent")
public final class FinancialReportController {

    private final FinancialReportService service;

    public FinancialReportController(FinancialReportService service) {
        this.service = service;
    }

    @PostMapping("/financial-report")
    public FinancialReportAnalysis financialReport(@RequestBody Request request) {
        return service.generate(request.code(), request.modelId());
    }

    public record Request(String code, String modelId) {
    }
}
