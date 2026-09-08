package com.astock.agent.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

/** 与行情数据隔离的、仅供分析内部使用的书本方法元数据。 */
@Service
public final class BookKnowledgeService {
    private static final String IMPLEMENTED = "IMPLEMENTED";
    private final List<KnowledgeEntry> entries;

    public BookKnowledgeService() {
        try (var input = BookKnowledgeService.class.getResourceAsStream("/knowledge/books.json")) {
            if (input == null) throw new IllegalStateException("Bundled book knowledge is missing");
            entries = List.copyOf(new ObjectMapper().readValue(input, new TypeReference<List<KnowledgeEntry>>() {}));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load bundled book knowledge", exception);
        }
    }

    public List<KnowledgeEntry> forCandlestick(List<String> patternIds) {
        return entries.stream().filter(entry -> entry.bookId().equals("nison"))
                .filter(entry -> IMPLEMENTED.equals(entry.implementationStatus()))
                .filter(entry -> entry.id().equals("nison-reversal") || entry.id().equals("nison-confluence")
                        || entry.patternIds().isEmpty()
                        || entry.patternIds().stream().anyMatch(patternIds::contains))
                .toList();
    }

    /** 固定研究任务由 Java 选择已实现的方法卡；方法只作为解释约束，不增加市场事实。 */
    public List<KnowledgeEntry> forOverallReport() {
        return entries.stream()
                .filter(entry -> entry.bookId().equals("nison"))
                .filter(entry -> IMPLEMENTED.equals(entry.implementationStatus()))
                .toList();
    }

}
