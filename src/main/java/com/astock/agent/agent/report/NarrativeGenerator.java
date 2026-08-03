package com.astock.agent.agent.report;

@FunctionalInterface
public interface NarrativeGenerator {
    ReportNarrativeDraft generate(ReportEvidencePackage evidence) throws Exception;

    default ReportNarrativeDraft repair(ReportEvidencePackage evidence, ReportNarrativeDraft draft,
            java.util.List<String> issues) throws Exception {
        return generate(evidence);
    }

    default String modelName() {
        return "configured-chat-model";
    }
}
