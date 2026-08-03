package com.astock.agent.agent;

import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.agent.overall.OverallReportGenerator;
import com.astock.agent.agent.overall.OverallReportService;
import com.astock.agent.agent.overall.OverallReportValidator;
import com.astock.agent.agent.overall.SpringAiOverallReportGenerator;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.analysis.ResearchAggregationService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        OverallReportGenerator generator = registry.forRole("overall-report")
                .map(model -> (OverallReportGenerator)
                        new SpringAiOverallReportGenerator(model.client(), model.modelName()))
                .orElse(null);
        return new OverallReportService(
                generator,
                tools,
                new OverallReportValidator(),
                new ModelFailureClassifier());
    }
}
