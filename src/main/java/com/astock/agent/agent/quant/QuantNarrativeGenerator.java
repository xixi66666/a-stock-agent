package com.astock.agent.agent.quant;

@FunctionalInterface
public interface QuantNarrativeGenerator {
    QuantNarrativeDraft generate(QuantReportFacts facts) throws Exception;

    default QuantNarrativeDraft repair(QuantReportFacts facts, QuantNarrativeDraft draft,
                                       java.util.List<String> issues) throws Exception {
        return generate(facts);
    }

    default String modelName() {
        return "configured-quant-model";
    }
}
