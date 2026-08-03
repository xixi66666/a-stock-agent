package com.astock.agent.agent.report;

import com.astock.agent.analysis.institutional.ReportEvidence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReportValidator {
    public static final String FIELD_GLOBAL = "global";
    public static final String FIELD_EXECUTIVE_SUMMARY = "executiveSummary";
    public static final String FIELD_TECHNICAL_AND_FLOW = "technicalAndFlow";
    public static final String FIELD_FUNDAMENTALS = "fundamentals";
    public static final String FIELD_VALUATION_AND_INDUSTRY = "valuationAndIndustry";
    public static final String FIELD_CATALYSTS = "catalysts";
    public static final String FIELD_RISKS = "risks";

    private static final Pattern EVIDENCE_ID = Pattern.compile("\\[([a-z0-9-]{3,80})\\]");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])[-+]?\\d+(?:\\.\\d+)?%?");
    private static final Pattern TRADE = Pattern.compile("买入|卖出|目标价|止损|止盈|仓位|建仓|保证收益|收益保证|稳赚");
    private static final Set<String> BLOCKING_ISSUES = Set.of(
            "EMPTY_DRAFT", "EMPTY_NARRATIVE", "NARRATIVE_TOO_LONG", "UNKNOWN_EVIDENCE",
            "UNSUPPORTED_NUMBER", "TRADE_INSTRUCTION");

    public ValidationResult validate(ReportNarrativeDraft draft, ReportEvidencePackage evidence) {
        Map<String, List<String>> issuesByField = new LinkedHashMap<>();
        if (draft == null || evidence == null) {
            addIssue(issuesByField, FIELD_GLOBAL, "EMPTY_DRAFT");
            return new ValidationResult(issuesByField);
        }

        validateField(issuesByField, FIELD_EXECUTIVE_SUMMARY, draft.executiveSummary(), evidence);
        validateField(issuesByField, FIELD_TECHNICAL_AND_FLOW, draft.technicalAndFlowNarrative(), evidence);
        validateField(issuesByField, FIELD_FUNDAMENTALS, draft.fundamentalNarrative(), evidence);
        validateField(issuesByField, FIELD_VALUATION_AND_INDUSTRY, draft.valuationAndIndustryNarrative(), evidence);
        validateListField(issuesByField, FIELD_CATALYSTS, draft.catalystNarratives(), evidence);
        validateListField(issuesByField, FIELD_RISKS, draft.riskNarratives(), evidence);

        String text = text(draft);
        if (text.isBlank()) addIssue(issuesByField, FIELD_GLOBAL, "EMPTY_NARRATIVE");
        if (!evidence.evidenceCatalog().isEmpty() && !hasEvidenceReference(text)) {
            addIssue(issuesByField, FIELD_GLOBAL, "MISSING_EVIDENCE_REFERENCE");
        }
        for (String conflict : evidence.conflicts()) {
            String key = conflict.length() > 8 ? conflict.substring(0, 8) : conflict;
            if (!text.contains(key)) {
                addIssue(issuesByField, FIELD_GLOBAL, "MISSING_CONFLICT");
                break;
            }
        }
        return new ValidationResult(issuesByField);
    }

    private void validateField(Map<String, List<String>> issuesByField, String field, String value,
            ReportEvidencePackage evidence) {
        if (value == null || value.isBlank()) {
            addIssue(issuesByField, field, "EMPTY_NARRATIVE");
            return;
        }
        String text = value;
        if (text.codePointCount(0, text.length()) > 12_000) addIssue(issuesByField, field, "NARRATIVE_TOO_LONG");
        validateEvidenceReferences(issuesByField, field, text, evidence);
        validateNumbers(issuesByField, field, text, evidence);
        if (TRADE.matcher(text).find()) addIssue(issuesByField, field, "TRADE_INSTRUCTION");
    }

    private void validateListField(Map<String, List<String>> issuesByField, String field, List<String> values,
            ReportEvidencePackage evidence) {
        if (values == null) return;
        for (String value : values) if (value != null && !value.isBlank()) validateField(issuesByField, field, value, evidence);
    }

    private void validateEvidenceReferences(Map<String, List<String>> issuesByField, String field, String text,
            ReportEvidencePackage evidence) {
        Matcher ids = EVIDENCE_ID.matcher(text);
        while (ids.find()) if (!evidence.evidenceCatalog().containsKey(ids.group(1))) {
            addIssue(issuesByField, field, "UNKNOWN_EVIDENCE");
        }
    }

    private void validateNumbers(Map<String, List<String>> issuesByField, String field, String text,
            ReportEvidencePackage evidence) {
        String evidenceText = evidence.evidenceCatalog().values().stream()
                .map(this::evidenceText).reduce("", (a, b) -> a + " " + b);
        Matcher numbers = NUMBER.matcher(text);
        while (numbers.find()) if (!evidenceText.contains(numbers.group())) {
            addIssue(issuesByField, field, "UNSUPPORTED_NUMBER");
        }
    }

    private static boolean hasEvidenceReference(String text) {
        return EVIDENCE_ID.matcher(text).find();
    }

    private static void addIssue(Map<String, List<String>> issuesByField, String field, String issue) {
        issuesByField.computeIfAbsent(field, ignored -> new ArrayList<>());
        List<String> issues = issuesByField.get(field);
        if (!issues.contains(issue)) issues.add(issue);
    }

    private String text(ReportNarrativeDraft draft) {
        List<String> values = new ArrayList<>();
        values.add(draft.executiveSummary()); values.add(draft.technicalAndFlowNarrative());
        values.add(draft.fundamentalNarrative()); values.add(draft.valuationAndIndustryNarrative());
        values.addAll(draft.catalystNarratives()); values.addAll(draft.riskNarratives());
        return values.stream().filter(value -> value != null).reduce("", (a, b) -> a + " " + b);
    }

    private String evidenceText(ReportEvidence evidence) {
        return evidence.id() + " " + evidence.title() + " " + evidence.interpretation();
    }

    public record ValidationResult(Map<String, List<String>> issuesByField) {
        public ValidationResult {
            if (issuesByField == null) {
                issuesByField = Map.of();
            } else {
                Map<String, List<String>> copy = new LinkedHashMap<>();
                issuesByField.forEach((field, issues) -> copy.put(field, issues == null ? List.of() : List.copyOf(issues)));
                issuesByField = Map.copyOf(copy);
            }
        }

        public boolean valid() {
            return blockingIssues().isEmpty();
        }

        public List<String> issues() {
            return flatten(false);
        }

        public List<String> blockingIssues() {
            return flatten(true);
        }

        public List<String> warnings() {
            Set<String> result = new LinkedHashSet<>(issues());
            result.removeAll(blockingIssues());
            return List.copyOf(result);
        }

        public boolean blocksField(String field) {
            return issuesByField.getOrDefault(field, List.of()).stream().anyMatch(BLOCKING_ISSUES::contains);
        }

        public boolean hasWarnings() {
            return !warnings().isEmpty();
        }

        private List<String> flatten(boolean blockingOnly) {
            Set<String> result = new LinkedHashSet<>();
            issuesByField.values().forEach(issues -> issues.forEach(issue -> {
                if (!blockingOnly || BLOCKING_ISSUES.contains(issue)) result.add(issue);
            }));
            return List.copyOf(result);
        }
    }
}
