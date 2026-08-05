package com.astock.agent.agent.report;

import java.util.List;
import com.astock.agent.analysis.institutional.AnalysisSignal;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.FundFlowSummary;

public record TechnicalAndFlowAnalysis(String narrative, List<com.astock.agent.analysis.institutional.ReportEvidence> evidence,
        List<ReportFact> facts, List<AnalysisSignal> signals, List<String> methodology,
        List<String> counterEvidence, List<String> limitations,
        DataSection<FundFlowSummary> fundFlowSummary) {
    public TechnicalAndFlowAnalysis(String narrative, List<com.astock.agent.analysis.institutional.ReportEvidence> evidence) {
        this(narrative, evidence, List.of(), List.of(), List.of(), List.of(), List.of(),
                DataSection.unavailable("fund flow summary not provided"));
    }
    public TechnicalAndFlowAnalysis(String narrative, List<com.astock.agent.analysis.institutional.ReportEvidence> evidence,
            List<ReportFact> facts) {
        this(narrative, evidence, facts, List.of(), List.of(), List.of(), List.of(),
                DataSection.unavailable("fund flow summary not provided"));
    }
    public TechnicalAndFlowAnalysis(String narrative, List<com.astock.agent.analysis.institutional.ReportEvidence> evidence,
            List<ReportFact> facts, List<AnalysisSignal> signals, List<String> methodology,
            List<String> counterEvidence, List<String> limitations) {
        this(narrative, evidence, facts, signals, methodology, counterEvidence, limitations,
                DataSection.unavailable("fund flow summary not provided"));
    }

    public TechnicalAndFlowAnalysis {
        narrative = narrative == null ? "" : narrative;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        facts = facts == null ? List.of() : List.copyOf(facts);
        signals = signals == null ? List.of() : List.copyOf(signals);
        methodology = methodology == null ? List.of() : List.copyOf(methodology);
        counterEvidence = counterEvidence == null ? List.of() : List.copyOf(counterEvidence);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        fundFlowSummary = fundFlowSummary == null
                ? DataSection.unavailable("fund flow summary not provided")
                : fundFlowSummary;
    }
}
