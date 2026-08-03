package com.astock.agent.analysis.institutional;

import java.util.List;
import java.util.Map;

public final class DeterministicAssessment {
    private final Direction direction;
    private final EvidenceStatus evidenceStatus;
    private final Map<String, EvidenceScore> dimensions;
    private final Map<AnalysisModule, ModuleAnalysis> moduleAnalyses;
    private final List<CoreDriver> coreDrivers;
    private final List<String> constraints;
    private final List<String> risks;
    private final List<String> conflicts;
    private final List<String> missingData;
    private final List<String> invalidationConditions;
    private final int internalScore;

    public DeterministicAssessment(Direction direction, EvidenceStatus evidenceStatus,
            Map<String, EvidenceScore> dimensions, List<CoreDriver> coreDrivers,
            List<String> constraints, List<String> risks, List<String> conflicts,
            List<String> missingData, List<String> invalidationConditions, int internalScore) {
        this(direction, evidenceStatus, dimensions, Map.of(), coreDrivers, constraints, risks,
                conflicts, missingData, invalidationConditions, internalScore);
    }

    public DeterministicAssessment(Direction direction, EvidenceStatus evidenceStatus,
            Map<String, EvidenceScore> dimensions, Map<AnalysisModule, ModuleAnalysis> moduleAnalyses,
            List<CoreDriver> coreDrivers, List<String> constraints, List<String> risks,
            List<String> conflicts, List<String> missingData, List<String> invalidationConditions,
            int internalScore) {
        this.direction = direction;
        this.evidenceStatus = evidenceStatus;
        this.dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
        this.moduleAnalyses = moduleAnalyses == null ? Map.of() : Map.copyOf(moduleAnalyses);
        this.coreDrivers = coreDrivers == null ? List.of() : List.copyOf(coreDrivers);
        this.constraints = constraints == null ? List.of() : List.copyOf(constraints);
        this.risks = risks == null ? List.of() : List.copyOf(risks);
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        this.missingData = missingData == null ? List.of() : List.copyOf(missingData);
        this.invalidationConditions = invalidationConditions == null ? List.of() : List.copyOf(invalidationConditions);
        this.internalScore = internalScore;
    }

    public Direction direction() { return direction; }
    public EvidenceStatus evidenceStatus() { return evidenceStatus; }
    public List<CoreDriver> coreDrivers() { return coreDrivers; }
    public List<String> constraints() { return constraints; }
    public List<String> risks() { return risks; }
    public List<String> conflicts() { return conflicts; }
    public List<String> missingData() { return missingData; }
    public List<String> invalidationConditions() { return invalidationConditions; }
    public Map<AnalysisModule, ModuleAnalysis> moduleAnalyses() { return moduleAnalyses; }
    public ModuleAnalysis moduleAnalysis(AnalysisModule module) { return moduleAnalyses.get(module); }

    /** 按名称读取单个维度，供报告组装使用；不会暴露内部综合分。 */
    public EvidenceScore evidenceScore(String name) { return dimensions.get(name); }

    // 仅供同包规则测试使用，避免内部综合分进入 API。
    int internalScore() { return internalScore; }
    EvidenceScore dimension(String name) { return dimensions.get(name); }
}
