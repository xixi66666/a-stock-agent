package com.astock.agent.agent;

import com.astock.agent.agent.financial.FinancialReportService;
import com.astock.agent.agent.financial.FinancialReportValidator;
import com.astock.agent.agent.finrobot.FinRobotResearchService;
import com.astock.agent.agent.uzi.UziProperties;
import com.astock.agent.agent.uzi.UziResearchService;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.agent.model.ModelConnectivityProbe;
import com.astock.agent.agent.model.ModelConnectivityRegistry;
import com.astock.agent.agent.model.ModelConnectivityStartupProbe;
import com.astock.agent.agent.report.InstitutionalReportComposer;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.analysis.ResearchAggregationService;
import com.astock.agent.analysis.ResearchGateway;
import com.astock.agent.analysis.institutional.ResearchJudgementEngine;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SecurityId;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 相关 Bean 的组装入口。
 *
 * <p>配置类只负责连接模型注册表、工具和 FinRobot 报告流水线，不把模型调用细节写进
 * Controller。没有 {@code finrobot-research} 模型时，仍然注册确定性可用的回退报告。</p>
 */
@Configuration
public class AgentConfiguration {

    @Bean
    com.astock.agent.agent.finrobot.OfficialFinRobotService officialFinRobotService(
            com.astock.agent.agent.finrobot.OfficialFinRobotProperties properties,
            com.astock.agent.agent.model.AiModelProperties models,
            StockAgentTools tools, ResearchGateway gateway) {
        return new com.astock.agent.agent.finrobot.OfficialFinRobotService(properties, models,
                new com.astock.agent.agent.finrobot.OfficialFinRobotWorker(properties, tools, gateway));
    }

    @Bean
    AgentStatusService agentStatusService(NamedChatClientRegistry registry) {
        return new AgentStatusService(registry);
    }

    @Bean
    StockAgentTools stockAgentTools(ResearchAggregationService research) {
        return new StockAgentTools(research);
    }

    @Bean
    FinRobotResearchService finRobotResearchService(
            NamedChatClientRegistry registry,
            StockAgentTools tools,
            UziResearchService uziResearchService) {
        return new FinRobotResearchService(
                registry, tools, new ResearchJudgementEngine(), new InstitutionalReportComposer(),
                new ModelFailureClassifier(), uziResearchService);
    }

    @Bean
    UziResearchService uziResearchService(
            UziProperties properties,
            NamedChatClientRegistry registry) {
        return new UziResearchService(properties, registry);
    }

    @Bean
    FinancialReportService financialReportService(
            NamedChatClientRegistry registry,
            StockAgentTools tools,
            ResearchGateway gateway,
            @Qualifier("financialHistoryCache")
            Cache<SecurityId, DataSection<FinancialStatementHistory>> historyCache) {
        return new FinancialReportService(gateway, tools, registry, historyCache,
                new FinancialReportValidator(), new ModelFailureClassifier());
    }

    @Bean
    ModelConnectivityRegistry modelConnectivityRegistry() {
        return new ModelConnectivityRegistry();
    }

    @Bean
    ModelConnectivityProbe modelConnectivityProbe(NamedChatClientRegistry registry,
            ModelConnectivityRegistry states, Clock clock) {
        // 启动时一次性探测；每模型限时 15 秒，失败只记录状态。
        return new ModelConnectivityProbe(registry, states, new ModelFailureClassifier(), clock,
                Duration.ofSeconds(15));
    }

    @Bean
    ModelConnectivityStartupProbe modelConnectivityStartupProbe(ModelConnectivityProbe probe) {
        return new ModelConnectivityStartupProbe(probe);
    }
}
