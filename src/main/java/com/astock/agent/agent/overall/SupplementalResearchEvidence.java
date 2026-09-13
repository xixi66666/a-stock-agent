package com.astock.agent.agent.overall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.List;

/**
 * 规范化快照之外的、可追溯的补充研究证据。
 *
 * <p>补充证据只能扩大模型的事实边界；它不会绕过报告的来源、数字、缺失和安全校验。</p>
 */
public record SupplementalResearchEvidence(
        String name,
        JsonNode facts,
        List<OverallSourceReference> sourceReferences,
        List<String> limitations) {

    public SupplementalResearchEvidence {
        name = name == null || name.isBlank() ? "supplemental-research" : name.trim();
        facts = facts == null ? MissingNode.getInstance() : facts.deepCopy();
        sourceReferences = sourceReferences == null ? List.of() : List.copyOf(sourceReferences);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
