package com.astock.agent.agent.quant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 检查模型文字是否越过事实、合规和无打分边界。 */
public final class QuantNarrativeValidator {
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern DATE = Pattern.compile("(?:20\\d{2})[-年/]\\d{1,2}(?:[-月/]\\d{1,2}日?)?");
    private static final List<String> TRADE_WORDS = List.of("买入", "卖出", "加仓", "减仓", "建仓", "止盈", "止损", "目标价", "buy", "sell");
    private static final List<String> SCORE_WORDS = List.of("评分", "得分", "排名", "打分", "score", "rank", "confidence", "aggregate", "置信度");
    private static final List<String> CERTAINTY_WORDS = List.of("确定上涨", "必然上涨", "一定上涨", "保证收益", "确定性收益", "必然", "一定");

    public Validation validate(QuantNarrativeDraft draft, QuantReportFacts facts) {
        if (draft == null) return new Validation(List.of("EMPTY_DRAFT"), List.of());
        String text = String.join("\n", draft.executiveSummary(), draft.marketEnvironment(), draft.securityPerformance(),
                draft.factorObservations(), draft.valuationAndFundamentals(), draft.capitalAndEvents(), draft.riskAndOutlook());
        Set<String> blocking = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        if (containsAny(text, TRADE_WORDS)) blocking.add("TRADE_INSTRUCTION");
        if (containsAny(text.toLowerCase(Locale.ROOT), SCORE_WORDS)) blocking.add("SCORING_CONTENT");
        if (hasUnsupportedNumber(text, facts)) blocking.add("UNSUPPORTED_NUMBER");
        if (hasUnavailableConclusion(text, facts)) blocking.add("UNAVAILABLE_CONCLUSION");
        if (DATE.matcher(text).find() && facts.sourceIds().isEmpty()) warnings.add("UNSOURCED_DATE");
        return new Validation(List.copyOf(blocking), List.copyOf(warnings));
    }

    private static boolean hasUnsupportedNumber(String text, QuantReportFacts facts) {
        Set<String> allowed = new LinkedHashSet<>();
        facts.metrics().values().forEach(metric -> {
            if (metric.value() != null) allowed.add(normalize(metric.value()));
            Matcher windows = NUMBER.matcher(metric.window());
            while (windows.find()) allowed.add(normalize(new BigDecimal(windows.group())));
            if (metric.asOf() != null) {
                allowed.add(String.valueOf(metric.asOf().getYear()));
                allowed.add(String.valueOf(metric.asOf().getMonthValue()));
                allowed.add(String.valueOf(metric.asOf().getDayOfMonth()));
            }
        });
        facts.benchmarkComparisons().values().forEach(comparison -> {
            if (comparison.excessReturnPercent() != null) allowed.add(normalize(comparison.excessReturnPercent()));
            if (comparison.beta() != null) allowed.add(normalize(comparison.beta()));
            if (comparison.informationRatio() != null) allowed.add(normalize(comparison.informationRatio()));
        });
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            String token = normalize(new BigDecimal(matcher.group()));
            if (!allowed.contains(token)) return true;
        }
        return false;
    }

    private static boolean hasUnavailableConclusion(String text, QuantReportFacts facts) {
        boolean missing = facts.limitations().stream().anyMatch(v -> v != null && !v.isBlank())
                || facts.metrics().values().stream().anyMatch(m -> m.availability() != MetricAvailability.AVAILABLE);
        return missing && containsAny(text, CERTAINTY_WORDS);
    }

    private static boolean containsAny(String text, List<String> words) {
        return words.stream().anyMatch(word -> text.contains(word));
    }

    private static String normalize(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public record Validation(List<String> blockingIssues, List<String> warnings) {
        public Validation {
            blockingIssues = blockingIssues == null ? List.of() : List.copyOf(blockingIssues);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }
}
