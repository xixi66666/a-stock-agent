package com.astock.agent.agent.financial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 财务叙事校验:证据边界、档位引用、交易指令、免责声明与长度。 */
public final class FinancialReportValidator {

    private static final Pattern TRADE = Pattern.compile(
            "买入|卖出|加仓|减仓|建仓|清仓|止盈|止损|目标价|保证收益|收益保证|稳赚"
                    + "|仓位(?:控制|调整|建议|保持|不超过|达到)|(?:建议|控制|调整|提高|降低|维持)[^。！？；\\n]{0,8}仓位");
    private static final Pattern NEGATED_TRADE_PREFIX = Pattern.compile(
            "(?:不建议|不推荐|不提供|不构成|不进行|不采取|不执行|不作出|不做|不含|不应|不宜|不能|不得|禁止|避免|并非|没有|暂无|未给出|未提供|未形成|不买入|不卖出|请勿|不可|无法|不允许|不涉及|不代表|不意味着)[^。！？；\\n]{0,8}$");
    private static final Pattern NEGATED_TRADE_SUFFIX = Pattern.compile(
            "^[^。！？；\\n]{0,8}(?:不可用|不可得|不提供|不构成|不代表|不属于|不存在|未提供|未形成|不适用|不涉及|并非|不是|无关)$");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])[-+]?\\d+(?:\\.\\d+)?%?");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public Validation validate(FinancialNarrativeDraft draft, FinancialEvidencePackage pack) {
        List<String> issues = new ArrayList<>();
        if (draft == null) {
            issues.add("EMPTY_REQUIRED_SECTION");
            return new Validation(issues);
        }
        if (blank(draft.tierInterpretation()) || blank(draft.signalCommentary())
                || blank(draft.trendCommentary()) || blank(draft.riskNotes())) {
            add(issues, "EMPTY_REQUIRED_SECTION");
        }
        if (!FinancialDeterministicComposer.REQUIRED_DISCLAIMER.equals(draft.disclaimer())) {
            add(issues, "INVALID_DISCLAIMER");
        }
        String text = String.join(" ", draft.tierInterpretation(), draft.signalCommentary(),
                draft.trendCommentary(), draft.riskNotes(), draft.disclaimer());
        if (text.codePointCount(0, text.length()) > 2000) {
            add(issues, "NARRATIVE_TOO_LONG");
        }
        if (containsTradeInstruction(text)) {
            add(issues, "TRADE_INSTRUCTION");
        }
        validateTierReference(draft.tierInterpretation(), pack, issues);
        validateNumbers(text, pack, issues);
        return new Validation(issues);
    }

    private void validateTierReference(String tierInterpretation,
            FinancialEvidencePackage pack, List<String> issues) {
        if (tierInterpretation == null) {
            return;
        }
        String required = pack.qualityScore().sufficientData()
                ? pack.qualityScore().tier() : "数据不足";
        if (!tierInterpretation.contains(required)) {
            add(issues, "MISSING_TIER_REFERENCE");
        }
    }

    private void validateNumbers(String text, FinancialEvidencePackage pack, List<String> issues) {
        Set<String> supported = packageNumbers(pack);
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            if (!supported.contains(normalizeNumber(matcher.group()))) {
                add(issues, "UNSUPPORTED_NUMBER");
            }
        }
    }

    private Set<String> packageNumbers(FinancialEvidencePackage pack) {
        Set<String> values = new HashSet<>();
        try {
            JsonNode root = MAPPER.valueToTree(pack);
            collectNumbers(root, values);
        } catch (Exception ignored) {
            return Set.of();
        }
        return values;
    }

    private void collectNumbers(JsonNode node, Set<String> values) {
        if (node == null) {
            return;
        }
        if (node.isNumber()) {
            values.add(normalizeNumber(node.asText()));
            return;
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> collectNumbers(child, values));
        }
    }

    private boolean containsTradeInstruction(String text) {
        Matcher matcher = TRADE.matcher(text);
        while (matcher.find()) {
            int prefixStart = Math.max(0, matcher.start() - 16);
            String prefix = text.substring(prefixStart, matcher.start());
            if (NEGATED_TRADE_PREFIX.matcher(prefix).find()) {
                continue;
            }
            int suffixEnd = Math.min(text.length(), matcher.end() + 16);
            String suffix = text.substring(matcher.end(), suffixEnd);
            if (NEGATED_TRADE_SUFFIX.matcher(suffix).find()) {
                continue;
            }
            return true;
        }
        return false;
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
        if (!issues.contains(issue)) {
            issues.add(issue);
        }
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
