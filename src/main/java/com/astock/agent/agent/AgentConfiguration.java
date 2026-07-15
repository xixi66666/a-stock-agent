package com.astock.agent.agent;

import com.astock.agent.analysis.ResearchAggregationService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class AgentConfiguration {

    @Bean AgentStatusService agentStatusService(Environment environment) {
        return new AgentStatusService(environment);
    }

    @Bean StockAgentTools stockAgentTools(ResearchAggregationService research) {
        return new StockAgentTools(research);
    }

    @Bean
    StockAnalysisAgent stockAnalysisAgent(
            ObjectProvider<ChatClient.Builder> builderProvider,
            AgentStatusService status,
            StockAgentTools tools) {
        ChatClient.Builder builder = status.status() == AgentAvailability.READY
                ? builderProvider.getIfAvailable()
                : null;
        ChatClient client = builder == null ? null : builder.build();
        return new StockAnalysisAgent(client, status, tools);
    }
}
