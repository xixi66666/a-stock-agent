package com.astock.agent.knowledge;

import java.util.List;

/** 经章节核对的研究笔记。summary 为项目概括，不是原书引文或当前行情事实。 */
public record KnowledgeEntry(String id, String bookId, String bookTitle, String author,
        String chapter, String sourceLocator, String revision, String reviewedOn, String domain,
        String title, String summary, String application, List<String> requiredEvidence,
        List<String> limitations, List<String> keywords, List<String> patternIds,
        String implementationStatus) {
    public KnowledgeEntry {
        requiredEvidence = List.copyOf(requiredEvidence);
        limitations = List.copyOf(limitations);
        keywords = List.copyOf(keywords);
        patternIds = List.copyOf(patternIds);
        implementationStatus = implementationStatus == null || implementationStatus.isBlank()
                ? "REFERENCE_ONLY" : implementationStatus;
    }
}
