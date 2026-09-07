package com.astock.agent.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 与行情数据隔离的、可离线使用的书本方法库。 */
@Service
public final class BookKnowledgeService {
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+(?:-[a-z]+)*|[\\p{IsHan}]+");
    private final List<KnowledgeEntry> entries;

    public BookKnowledgeService() {
        try (var input = BookKnowledgeService.class.getResourceAsStream("/knowledge/books.json")) {
            if (input == null) throw new IllegalStateException("Bundled book knowledge is missing");
            entries = List.copyOf(new ObjectMapper().readValue(input, new TypeReference<List<KnowledgeEntry>>() {}));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load bundled book knowledge", exception);
        }
    }

    public SearchResult search(String query, String bookId, int limit) {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 200
                || query.codePoints().anyMatch(Character::isISOControl) || limit < 1 || limit > 10) {
            throw new IllegalArgumentException("问题须为 1—200 个字符，返回条数须为 1—10");
        }
        if (bookId != null && !bookId.isBlank() && !Set.of("nison", "marks", "naval").contains(bookId)) {
            throw new IllegalArgumentException("书籍须为 nison、marks 或 naval");
        }
        String normalized = normalize(query);
        Set<String> terms = terms(normalized);
        List<Match> matches = entries.stream()
                .filter(entry -> bookId == null || bookId.isBlank() || entry.bookId().equals(bookId))
                .map(entry -> score(entry, normalized, terms))
                // 单个正文二字片段（例如“的影”）不能支持相关性，避免跨书误命中。
                .filter(match -> match.relevanceScore() >= 4)
                .sorted(Comparator.comparingInt(Match::relevanceScore).reversed()
                        .thenComparing(match -> match.entry().id()))
                .limit(limit).toList();
        return new SearchResult(matches.isEmpty() ? "NO_MATCH" : "MATCHED", matches);
    }

    public Optional<KnowledgeEntry> find(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    public List<KnowledgeEntry> forCandlestick(List<String> patternIds) {
        return entries.stream().filter(entry -> entry.bookId().equals("nison"))
                .filter(entry -> entry.id().equals("nison-reversal") || entry.id().equals("nison-confluence")
                        || entry.patternIds().stream().anyMatch(patternIds::contains))
                .toList();
    }

    /** 固定研究任务的检索主题由 Java 选择；方法只作为解释约束，不增加市场事实。 */
    public List<KnowledgeEntry> forOverallReport() {
        return java.util.stream.Stream.of(search("反转 风险报偿", "nison", 2),
                        search("周期位置", "marks", 1), search("清晰思考", "naval", 1))
                .flatMap(result -> result.matches().stream()).map(Match::entry).distinct().toList();
    }

    private static Match score(KnowledgeEntry entry, String query, Set<String> terms) {
        String title = normalize(entry.title());
        String content = normalize(entry.summary() + " " + entry.application());
        Set<String> matched = new LinkedHashSet<>();
        int score = 0;
        for (String keyword : entry.keywords()) {
            String term = normalize(keyword);
            if (query.contains(term)) { score += 12; matched.add(keyword); }
        }
        for (String term : terms) {
            if (title.contains(term)) { score += 4; matched.add(term); }
            else if (content.contains(term)) { score += 1; matched.add(term); }
        }
        return new Match(entry, score, List.copyOf(matched));
    }

    private static Set<String> terms(String text) {
        Set<String> result = new LinkedHashSet<>();
        var matcher = WORD.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            int[] points = word.codePoints().toArray();
            if (Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN) {
                for (int i = 0; i + 1 < points.length; i++) result.add(new String(points, i, 2));
            } else result.add(word);
        }
        result.removeAll(Set.of("什么", "如何", "怎么", "是否", "可以", "分析", "当前", "the", "is", "a", "of"));
        return result;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }

    public record Match(KnowledgeEntry entry, int relevanceScore, List<String> matchedTerms) {}
    public record SearchResult(String status, List<Match> matches) {}
}
