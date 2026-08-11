package com.astock.agent.agent;

import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.agent.overall.OverallReportService;
import com.astock.agent.agent.overall.OverallReportValidator;
import com.astock.agent.agent.quant.QuantFactsCalculator;
import com.astock.agent.agent.quant.QuantReportComposer;
import com.astock.agent.agent.quant.QuantResearchReportService;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.analysis.ResearchAggregationService;
import com.astock.agent.marketdata.provider.BenchmarkDataGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 相关 Bean 的组装入口。
 *
 * <p>配置类只负责连接模型注册表、工具和报告服务，不把模型调用细节写进 Controller。
 * 没有 {@code institutional-report} 模型时，仍然注册确定性可用的报告 Agent。</p>
 */
@Configuration
public class AgentConfiguration {

    @Bean
    AgentStatusService agentStatusService(NamedChatClientRegistry registry) {
        return new AgentStatusService(registry);
    }

    @Bean
    StockAgentTools stockAgentTools(ResearchAggregationService research) {
        return new StockAgentTools(research);
    }

    @Bean
    StockAnalysisAgent stockAnalysisAgent(
            NamedChatClientRegistry registry,
            AgentStatusService status,
            StockAgentTools tools) {
        // 业务角色决定研究报告使用哪个命名模型；浏览器不会直接接触 ChatClient 配置。
        var named = registry.forRole("institutional-report");
        if (named.isEmpty()) {
            return new StockAnalysisAgent((ChatClient) null, status, tools);
        }
        var model = named.orElseThrow();
        return new StockAnalysisAgent(model.client(), model.modelName(), status, tools);
    }

    @Bean
    OverallReportService overallReportService(
            NamedChatClientRegistry registry,
            StockAgentTools tools) {
        return new OverallReportService(
                registry,
                tools,
                new OverallReportValidator(),
                new ModelFailureClassifier());
    }

    @Bean
    QuantResearchReportService quantResearchReportService(
            NamedChatClientRegistry registry,
            StockAgentTools tools,
            BenchmarkDataGateway benchmarkDataGateway) {
        return new QuantResearchReportService(tools, benchmarkDataGateway,
                new QuantFactsCalculator(), new QuantReportComposer(), registry);
    }
}
