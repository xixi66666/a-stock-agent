package com.astock.agent.agent.overall;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.SectionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 对总体报告执行确定性、证据边界和安全校验。 */
/**
 * 对总体报告执行确定性、证据边界和安全校验。
 *
 * <p>除了必填章节和交易指令，还检查来源分区、Provider、快照中的数字和日期、
 * 核心数据限制以及固定免责声明。</p>
 */
public final class OverallReportValidator {
    private static final Pattern TRADE = Pattern.compile(
            "买入|卖出|加仓|减仓|建仓|清仓|止盈|止损|目标价|保证收益|收益保证|稳赚"
                    + "|仓位(?:控制|调整|建议|保持|不超过|达到)|(?:建议|控制|调整|提高|降低|维持)[^。！？；\\n]{0,8}仓位");
    private static final Pattern NEGATED_TRADE_PREFIX = Pattern.compile(
            "(?:不建议|不推荐|不提供|不构成|不进行|不采取|不执行|不作出|不做|不含|不应|不宜|不能|不得|禁止|避免|并非|没有|暂无|未给出|未提供|未形成|不买入|不卖出|请勿|不可|无法|不允许|不涉及|不代表|不意味着)[^。！？；\\n]{0,8}$");
    private static final Pattern NEGATED_TRADE_SUFFIX = Pattern.compile(
            "^[^。！？；\\n]{0,8}(?:不可用|不可得|不提供|不构成|不代表|不属于|不存在|未提供|未形成|不适用|不涉及|并非|不是|无关)$");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])[-+]?\\d+(?:\\.\\d+)?%?");
    private static final Pattern DATE_TOKEN = Pattern.compile(
            "(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?:T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z?)?(?!\\d)");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public Validation validate(OverallReportDraft draft, StockResearchSnapshot snapshot) {
        // 先做结构和安全检查，再做依赖快照的来源、数字和缺失限制检查。
        List<String> issues = new ArrayList<>();
        if (draft == null) {
            issues.add("EMPTY_REQUIRED_SECTION");
            return new Validation(issues);
        }

        List<String> required = List.of(
                draft.overallConclusion(), draft.dataQualitySummary(), draft.companyAndFundamentals(),
                draft.technicalAndCapital(), draft.valuationAndIndustry(), draft.eventsAndSentiment());
        if (required.stream().anyMatch(this::blank)) add(issues, "EMPTY_REQUIRED_SECTION");

        String text = reportText(draft);
        if (containsTradeInstruction(text)) add(issues, "TRADE_INSTRUCTION");
        if (!OverallResearchReport.REQUIRED_DISCLAIMER.equals(draft.disclaimer())) {
            add(issues, "INVALID_DISCLAIMER");
        }

        if (snapshot != null) {
            validateSources(draft.sourceReferences(), snapshot, issues);
            validateNumbers(text, snapshot, issues);
            validateCoreLimitations(draft.conflictsAndMissingData(), snapshot, issues);
        }
        return new Validation(issues);
    }

    private void validateSources(List<OverallSourceReference> references,
            StockResearchSnapshot snapshot, List<String> issues) {
        // 引用必须指向快照真实存在的分区，Provider 也必须与该分区 provenance 一致。
        Map<String, DataSection<?>> sections = sections(snapshot);
        for (OverallSourceReference reference : references) {
            if (reference == null || !sections.containsKey(reference.section())) {
                add(issues, "UNKNOWN_SOURCE_REFERENCE");
                continue;
            }
            DataSection<?> section = sections.get(reference.section());
            if (section == null) {
                add(issues, "UNKNOWN_SOURCE_REFERENCE");
                continue;
            }
            String provider = section.provenance().map(p -> p.provider()).orElse(null);
            if (provider == null || !provider.equals(reference.provider())) {
                add(issues, "UNKNOWN_SOURCE_REFERENCE");
            }
        }
    }

    private void validateNumbers(String text, StockResearchSnapshot snapshot, List<String> issues) {
        Set<String> supported = snapshotNumbers(snapshot);
        Set<String> supportedDates = snapshotDateTokens(snapshot);
        StringBuilder withoutDates = new StringBuilder();
        Matcher dates = DATE_TOKEN.matcher(text);
        int last = 0;
        while (dates.find()) {
            if (!supportedDates.contains(dates.group())) add(issues, "UNSUPPORTED_NUMBER");
            withoutDates.append(text, last, dates.start()).append(' ');
            last = dates.end();
        }
        withoutDates.append(text, last, text.length());

        Matcher matcher = NUMBER.matcher(withoutDates);
        while (matcher.find()) {
            if (isStructuralListNumber(withoutDates.toString(), matcher)) continue;
            if (!supported.contains(normalizeNumber(matcher.group()))) {
                add(issues, "UNSUPPORTED_NUMBER");
            }
        }
    }

    private boolean containsTradeInstruction(String text) {
        Matcher matcher = TRADE.matcher(text);
        while (matcher.find()) {
            int prefixStart = Math.max(0, matcher.start() - 16);
            String prefix = text.substring(prefixStart, matcher.start());
            if (NEGATED_TRADE_PREFIX.matcher(prefix).find()) continue;

            int suffixEnd = Math.min(text.length(), matcher.end() + 16);
            String suffix = text.substring(matcher.end(), suffixEnd);
            if (NEGATED_TRADE_SUFFIX.matcher(suffix).find()) continue;
            return true;
        }
        return false;
    }

    private boolean isStructuralListNumber(String text, Matcher matcher) {
        int index = matcher.end();
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        if (index >= text.length()) return false;
        char marker = text.charAt(index);
        if (marker != '.' && marker != ')' && marker != '）' && marker != '、') return false;
        int before = matcher.start() - 1;
        if (before < 0 || Character.isWhitespace(text.charAt(before))) return true;
        return (text.charAt(before) == '(' || text.charAt(before) == '（')
                && (marker == ')' || marker == '）');
    }

    private Set<String> snapshotNumbers(StockResearchSnapshot snapshot) {
        Set<String> values = new HashSet<>();
        try {
            JsonNode root = MAPPER.valueToTree(snapshot);
            collectNumbers(root, values);
        } catch (Exception ignored) {
            return Set.of();
        }
        if (snapshot.security() != null && snapshot.security().code() != null) {
            values.add(normalizeNumber(snapshot.security().code()));
        }
        return values;
    }

    private Set<String> snapshotDateTokens(StockResearchSnapshot snapshot) {
        Set<String> values = new HashSet<>();
        try {
            collectDateTokens(MAPPER.writeValueAsString(snapshot), values);
            if (snapshot.fetchedAt() != null) collectDateTokens(snapshot.fetchedAt().toString(), values);
            return values;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private void collectDateTokens(String text, Set<String> values) {
        if (text == null) return;
        Matcher matcher = DATE_TOKEN.matcher(text);
        while (matcher.find()) values.add(matcher.group());
    }

    private void collectNumbers(JsonNode node, Set<String> values) {
        if (node == null) return;
        if (node.isNumber()) {
            values.add(normalizeNumber(node.asText()));
            return;
        }
        if (node.isContainerNode()) node.elements().forEachRemaining(child -> collectNumbers(child, values));
    }

    private void validateCoreLimitations(List<String> conflicts, StockResearchSnapshot snapshot,
            List<String> issues) {
        String text = String.join(" ", conflicts).toLowerCase();
        if (isUnavailable(snapshot.quote()) && !text.contains("quote")) {
            add(issues, "MISSING_CORE_DATA_LIMITATION");
        }
        if (isUnavailable(snapshot.bars()) && !text.contains("bars")) {
            add(issues, "MISSING_CORE_DATA_LIMITATION");
        }
    }

    private static Map<String, DataSection<?>> sections(StockResearchSnapshot snapshot) {
        Map<String, DataSection<?>> result = new LinkedHashMap<>();
        result.put("quote", snapshot.quote());
        result.put("bars", snapshot.bars());
        result.put("technical", snapshot.technical());
        result.put("sectors", snapshot.sectors());
        result.put("industryValuation", snapshot.industryValuation());
        result.put("fundFlow", snapshot.fundFlow());
        result.put("capital", snapshot.capital());
        result.put("fundamentals", snapshot.fundamentals());
        result.put("research", snapshot.research());
        result.put("news", snapshot.news());
        result.put("announcements", snapshot.announcements());
        return result;
    }

    private static boolean isUnavailable(DataSection<?> section) {
        return section != null && section.status() == SectionStatus.UNAVAILABLE;
    }

    private String reportText(OverallReportDraft draft) {
        List<String> values = new ArrayList<>();
        values.add(draft.overallConclusion());
        values.add(draft.dataQualitySummary());
        values.add(draft.companyAndFundamentals());
        values.add(draft.technicalAndCapital());
        values.add(draft.valuationAndIndustry());
        values.add(draft.eventsAndSentiment());
        values.addAll(draft.bullishEvidence());
        values.addAll(draft.bearishEvidence());
        values.addAll(draft.riskFactors());
        values.addAll(draft.scenarios().keySet());
        values.addAll(draft.scenarios().values());
        values.addAll(draft.conflictsAndMissingData());
        return values.stream().filter(value -> value != null).reduce("", (a, b) -> a + " " + b);
    }

    private static String normalizeNumber(String value) {
        String token = value.replace("%", "");
        try {
            return new BigDecimal(token).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return token;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void add(List<String> issues, String issue) {
        if (!issues.contains(issue)) issues.add(issue);
    }

    public record Validation(List<String> issues) {
        public Validation {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean blocking() {
            return !issues.isEmpty();
        }
    }
}
