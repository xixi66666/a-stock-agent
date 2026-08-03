package com.astock.agent.analysis.institutional;

import com.astock.agent.agent.report.ReportFact;
import java.util.List;

/** 单一研究维度的事实、判断过程和适用边界。 */
public record ModuleAnalysis(
        AnalysisModule module,
        Direction direction,
        String conclusion,
        String confidence,
        List<ReportFact> facts,
        List<AnalysisSignal> signals,
        List<String> methodology,
        List<String> counterEvidence,
        List<String> limitations,
        List<String> sourceIds) {

    public ModuleAnalysis {
        if (module == null || direction == null || conclusion == null || conclusion.isBlank()
                || confidence == null || confidence.isBlank()) {
            throw new IllegalArgumentException("Module, direction, conclusion and confidence are required");
        }
        facts = copy(facts);
        signals = copy(signals);
        methodology = copy(methodology);
        counterEvidence = copy(counterEvidence);
        limitations = copy(limitations);
        sourceIds = copy(sourceIds);
    }

    private static <T> List<T> copy(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
