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

    /** 引文逐字核对用户提供的中文版章节；仅解释轮廓，不据此强行命名形态。 */
    public List<BookExcerpt> forSessionShape(String shape) {
        String chapter = "第三章 蜡烛图的绘制方法";
        String locator = "content/chapters/009-section-009.md";
        String text;
        String scope;
        switch (shape) {
            case "长上影线" -> {
                chapter = "第五章 星线";
                locator = "content/chapters/011-section-011.md";
                text = "同一种形状的蜡烛线既可以是看涨的，也可以是看跌的，取决于在其出现之前的趋势方向";
                scope = "节选自倒锤子线与流星线的比较；长上影轮廓本身不等于这两种命名形态。";
            }
            case "长下影线" -> {
                chapter = "第四章 反转形态";
                locator = "content/chapters/010-section-010.md";
                text = "这两种蜡烛线都既可能是看涨的，也可能是看跌的，具体情况要由它们在趋势中所处的位置来决定。";
                scope = "原文讨论伞形线；长下影轮廓本身不等于锤子线或上吊线。";
            }
            case "十字线轮廓" -> {
                text = "当某个交易日的开市价和收市价处于同一水平，或者当开市价与收市价的水平极为相近时，当日的蜡烛线就变成了一根十字蜡烛线。";
                scope = "原文说明十字线构成；项目的 5% 判定阈值属于工程近似。";
            }
            case "小实体线" -> {
                text = "因为短实体只代表一个时间单位内的市场行为，我们应该把它看作一条试探性的线索。";
                scope = "原文讨论短实体的证据边界；项目的 30% 判定阈值属于工程近似。";
            }
            default -> {
                text = "对日本人来说，实体的部分代表了“实质性的价格运动”。";
                scope = "这是实体的基础定义，不是对当前走势的结论；无振幅时无法计算实体占比。";
            }
        }
        return List.of(new BookExcerpt("日本蜡烛图技术", "史蒂夫·尼森", chapter, text, locator, scope));
    }

    /** 固定研究任务由 Java 选择已实现的方法卡；方法只作为解释约束，不增加市场事实。 */
    public List<KnowledgeEntry> forOverallReport() {
        return entries.stream()
                .filter(entry -> entry.bookId().equals("nison"))
                .filter(entry -> IMPLEMENTED.equals(entry.implementationStatus()))
                .toList();
    }

}
