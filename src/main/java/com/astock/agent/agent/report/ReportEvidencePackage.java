package com.astock.agent.agent.report;

import com.astock.agent.analysis.institutional.Direction;
import com.astock.agent.analysis.institutional.EvidenceStatus;
import com.astock.agent.analysis.institutional.AnalysisModule;
import com.astock.agent.analysis.institutional.CoreDriver;
import com.astock.agent.analysis.institutional.ModuleAnalysis;
import com.astock.agent.analysis.institutional.ReportEvidence;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ReportEvidencePackage(
        String securityCode,
        String securityName,
        String horizon,
        Direction direction,
        EvidenceStatus evidenceStatus,
        Map<String, ReportEvidence> evidenceCatalog,
        List<String> constraints,
        List<String> risks,
        List<String> conflicts,
        List<String> missingData,
        List<String> invalidationConditions,
        List<ReportEvidence> news,
        List<ReportEvidence> announcements,
        Instant snapshotAt,
        String ruleVersion,
        Map<AnalysisModule, ModuleAnalysis> moduleAnalyses,
        List<CoreDriver> coreDrivers) {
    public ReportEvidencePackage(String securityCode, String securityName, String horizon,
            Direction direction, EvidenceStatus evidenceStatus, Map<String, ReportEvidence> evidenceCatalog,
            List<String> constraints, List<String> risks, List<String> conflicts, List<String> missingData,
            List<String> invalidationConditions, List<ReportEvidence> news, List<ReportEvidence> announcements,
            Instant snapshotAt, String ruleVersion) {
        this(securityCode, securityName, horizon, direction, evidenceStatus, evidenceCatalog, constraints,
                risks, conflicts, missingData, invalidationConditions, news, announcements, snapshotAt,
                ruleVersion, Map.of(), List.of());
    }

    public ReportEvidencePackage {
        evidenceCatalog = evidenceCatalog == null ? Map.of() : Map.copyOf(evidenceCatalog);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        risks = risks == null ? List.of() : List.copyOf(risks);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        missingData = missingData == null ? List.of() : List.copyOf(missingData);
        invalidationConditions = invalidationConditions == null ? List.of() : List.copyOf(invalidationConditions);
        news = news == null ? List.of() : List.copyOf(news);
        announcements = announcements == null ? List.of() : List.copyOf(announcements);
        moduleAnalyses = moduleAnalyses == null ? Map.of() : Map.copyOf(moduleAnalyses);
        coreDrivers = coreDrivers == null ? List.of() : List.copyOf(coreDrivers);
    }
}
