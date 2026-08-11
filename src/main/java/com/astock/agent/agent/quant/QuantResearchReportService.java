package com.astock.agent.agent.quant;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.marketdata.model.BenchmarkId;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.provider.BenchmarkDataGateway;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** 单股量化研究报告编排服务。 */
public final class QuantResearchReportService {
    private static final String ROLE = "institutional-report";

    @FunctionalInterface
    interface GeneratorFactory {
        QuantNarrativeGenerator create(NamedChatClientRegistry.NamedModel model);
    }

    private final StockAgentTools tools;
    private final BenchmarkDataGateway benchmarkGateway;
    private final QuantFactsCalculator calculator;
    private final QuantReportComposer composer;
    private final QuantNarrativeValidator validator;
    private final NamedChatClientRegistry registry;
    private final GeneratorFactory generatorFactory;

    public QuantResearchReportService(StockAgentTools tools, BenchmarkDataGateway benchmarkGateway,
                                      QuantFactsCalculator calculator, QuantReportComposer composer,
                                      QuantNarrativeValidator validator, NamedChatClientRegistry registry) {
        this(tools, benchmarkGateway, calculator, composer, validator, registry,
                model -> new SpringAiQuantNarrativeGenerator(model.client(), model.modelName()));
    }

    public QuantResearchReportService(StockAgentTools tools, BenchmarkDataGateway benchmarkGateway,
                                      QuantFactsCalculator calculator, QuantReportComposer composer,
                                      NamedChatClientRegistry registry) {
        this(tools, benchmarkGateway, calculator, composer, new QuantNarrativeValidator(), registry);
    }

    QuantResearchReportService(StockAgentTools tools, BenchmarkDataGateway benchmarkGateway,
                               QuantFactsCalculator calculator, QuantReportComposer composer,
                               QuantNarrativeValidator validator, NamedChatClientRegistry registry,
                               GeneratorFactory generatorFactory) {
        this.tools = Objects.requireNonNull(tools, "tools is required");
        this.benchmarkGateway = benchmarkGateway;
        this.calculator = Objects.requireNonNull(calculator, "calculator is required");
        this.composer = Objects.requireNonNull(composer, "composer is required");
        this.validator = Objects.requireNonNull(validator, "validator is required");
        this.registry = registry == null ? new NamedChatClientRegistry(null, null) : registry;
        this.generatorFactory = Objects.requireNonNull(generatorFactory, "generatorFactory is required");
    }

    public QuantResearchReport generate(String code) {
        var snapshot = tools.getResearchSnapshot(code);
        Map<String, List<DailyBar>> benchmarks = new LinkedHashMap<>();
        if (benchmarkGateway != null) {
            for (BenchmarkId id : BenchmarkId.values()) {
                try {
                    var section = benchmarkGateway.bars(id);
                    benchmarks.put(id.name(), section.payload().orElse(null));
                } catch (RuntimeException ignored) {
                    // 基准是可选证据；单股报告不能因单一基准失败而整体失败。
                    benchmarks.put(id.name(), null);
                }
            }
        }
        QuantReportFacts facts = calculator.calculate(snapshot, benchmarks);
        Optional<NamedChatClientRegistry.NamedModel> model = registry.forRole(ROLE);
        if (model.isEmpty()) return composer.fallback(snapshot, facts);
        try {
            QuantNarrativeGenerator generator = generatorFactory.create(model.orElseThrow());
            QuantNarrativeDraft draft = generator.generate(facts);
            QuantNarrativeValidator.Validation validation = validator.validate(draft, facts);
            if (!validation.blockingIssues().isEmpty()) {
                draft = generator.repair(facts, draft, validation.blockingIssues());
                validation = validator.validate(draft, facts);
            }
            return composer.compose(snapshot, facts, draft, validation, generator.modelName());
        } catch (Exception ignored) {
            return composer.fallback(snapshot, facts);
        }
    }
}
