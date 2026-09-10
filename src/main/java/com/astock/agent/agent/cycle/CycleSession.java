package com.astock.agent.agent.cycle;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.ai.tool.annotation.Tool;

/** 每次研究独占工具实例，证据、进度和阅读记录不跨证券共享。 */
public final class CycleSession {
    public static final List<String> SECTIONS = List.of("quote", "bars", "technical", "sectors",
            "industryValuation", "fundFlow", "fundFlowSummary", "capital", "fundamentals", "research", "news", "announcements");
    public static final Set<String> DIMENSIONS = Set.of("economy", "earnings", "industry", "credit", "psychology", "risk", "valuation");
    public static final List<String> LIMITATIONS = List.of(
            "本次工具未接入宏观时间序列、整体信贷条件及行业供需历史，相关维度可能证据不足。",
            "个股资金、新闻与技术指标只能作为局部证据，不能直接代表整体市场心理或信贷周期。",
            "事实引用和阅读流程已做结构检查，模型推论仍需人工复核，不代表已验证周期定位。");
    private final CycleLibrary library;
    private final StockResearchSnapshot snapshot;
    private final JsonNode evidence;
    private final Consumer<String> progress;
    private final List<Event> events = new ArrayList<>();
    private final Set<String> queries = new HashSet<>();
    private final Map<String, Integer> chapterOffsets = new LinkedHashMap<>();
    private final Map<String, String> chapterTitles = new LinkedHashMap<>();
    private final Set<String> observed = new LinkedHashSet<>();
    private boolean topicsRead;
    private int calls;

    public CycleSession(CycleLibrary library, StockResearchSnapshot snapshot, Consumer<String> progress) {
        this.library = library;
        this.snapshot = snapshot;
        this.progress = progress;
        evidence = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).valueToTree(snapshot);
    }
    public String instructions() throws IOException { return library.instructions(); }
    public StockResearchSnapshot snapshot() { return snapshot; }
    private void guard() {
        if (Thread.currentThread().isInterrupted() || ++calls > 48) throw new IllegalStateException("研究工具调用已达到限制");
    }
    private synchronized void event(String action, String detail) {
        events.add(new Event(Instant.now(), action, detail));
        progress.accept(action);
    }
    @Tool(description = "检索《周期》原书。使用用户原词、相近术语、核心周期概念分别检索至少三次。只接受简短关键词。")
    public List<CycleLibrary.Hit> cycleSearch(String query) throws IOException, InterruptedException {
        guard();
        var hits = library.search(query);
        queries.add(query.strip());
        event("检索原书", query + "：" + hits.size() + " 个章节命中");
        return hits;
    }
    @Tool(description = "至少检索一次后读取中文主题导航，之后再读取相关章节。")
    public String cycleReadTopics() throws IOException {
        guard();
        if (queries.isEmpty()) throw new IllegalStateException("先检索原书");
        String text = library.topics();
        topicsRead = true;
        event("阅读主题导航", "中文主题索引");
        return text;
    }
    @Tool(description = "读取原书章节。chapterId 为检索返回的文件名，例如017-13.md。offset首次为0，后续必须使用返回的nextOffset，直到-1。至少完整读取两个相关章节。")
    public CycleLibrary.Page cycleReadChapter(String chapterId, int offset) throws IOException {
        guard();
        if (!topicsRead) throw new IllegalStateException("先阅读主题导航");
        if (offset != chapterOffsets.getOrDefault(chapterId, 0)) throw new IllegalArgumentException("请按顺序阅读章节");
        var page = library.chapter(chapterId, offset);
        chapterOffsets.put(chapterId, page.nextOffset());
        chapterTitles.put(chapterId, page.title());
        event("阅读原文章节", page.title() + (page.nextOffset() == -1 ? "：已读完" : "：继续阅读"));
        return page;
    }
    @Tool(description = "读取本次股票研究证据，保留payload、status、provenance。section只允许quote,bars,technical,sectors,industryValuation,fundFlow,fundFlowSummary,capital,fundamentals,research,news,announcements。没有宏观/信贷工具时必须披露缺项，不允许凭记忆补齐。")
    public Evidence cycleReadEvidence(String section) {
        guard();
        if (!SECTIONS.contains(section)) throw new IllegalArgumentException("未知证据分区");
        observed.add(section);
        event("核对市场证据", section);
        return new Evidence(section, evidence.path(section));
    }
    public synchronized List<Event> trace() { return List.copyOf(events); }
    public List<Chapter> chapters() {
        return chapterTitles.entrySet().stream().filter(e -> chapterOffsets.get(e.getKey()) == -1)
                .map(e -> new Chapter(e.getKey(), e.getValue())).toList();
    }
    public List<Evidence> evidence() { return observed.stream().map(id -> new Evidence(id, evidence.path(id))).toList(); }
    public void verifyWorkflow() {
        if (queries.size() < 3 || !topicsRead || chapters().size() < 2) {
            throw new IllegalStateException("原书研究未完成：需要分别检索、阅读主题导航及至少两个完整章节");
        }
        if (!observed.containsAll(SECTIONS)) throw new IllegalStateException("尚未检查全部研究证据分区");
    }
    public void validate(CycleReport report) {
        verifyWorkflow();
        if (report == null || blank(report.conclusion()) || blank(report.calibration()) || report.dimensions() == null
                || report.dimensions().size() != DIMENSIONS.size() || report.scenarios() == null || report.scenarios().size() != 3
                || report.conflicts() == null || report.watchItems() == null || report.watchItems().isEmpty()
                || report.limitations() == null || report.limitations().isEmpty()) throw new IllegalStateException("研究报告结构不完整");
        Set<String> seen = new HashSet<>();
        Set<String> read = new HashSet<>(chapters().stream().map(Chapter::id).toList());
        for (var dimension : report.dimensions()) {
            if (dimension == null || !DIMENSIONS.contains(dimension.id()) || !seen.add(dimension.id()) || blank(dimension.title())
                    || blank(dimension.bookView()) || blank(dimension.analysis()) || dimension.chapterIds() == null
                    || dimension.chapterIds().isEmpty() || !read.containsAll(dimension.chapterIds()) || dimension.facts() == null) {
                throw new IllegalStateException("研究维度或原书引用无效");
            }
            for (var fact : dimension.facts()) {
                if (fact == null || blank(fact.text()) || fact.evidenceIds() == null || fact.evidenceIds().isEmpty()
                        || !observed.containsAll(fact.evidenceIds()) || fact.evidenceIds().stream().anyMatch(id ->
                            evidence.path(id).isMissingNode() || evidence.path(id).isNull()
                            || "UNAVAILABLE".equals(evidence.path(id).path("status").asText()))) {
                    throw new IllegalStateException("市场事实引用无效或数据不可用");
                }
            }
        }
        if (report.scenarios().stream().anyMatch(s -> s == null || blank(s.condition()) || blank(s.interpretation()))) {
            throw new IllegalStateException("情景条件不完整");
        }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public record Event(Instant at, String action, String detail) {}
    public record Chapter(String id, String title) {}
    public record Evidence(String id, JsonNode data) {}
}
