package com.astock.agent.agent.report;

import com.astock.agent.analysis.institutional.ReportEvidence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReportValidator {
    private static final Pattern EVIDENCE_ID = Pattern.compile("\\[([a-z0-9-]{3,80})\\]");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])[-+]?\\d+(?:\\.\\d+)?%?");
    private static final Pattern TRADE = Pattern.compile("买入|卖出|目标价|止损|止盈|仓位|建仓|保证收益|收益保证|稳赚");

    public ValidationResult validate(ReportNarrativeDraft draft, ReportEvidencePackage evidence) {
        Set<String> issues = new LinkedHashSet<>();
        if (draft == null || evidence == null) {
            issues.add("EMPTY_DRAFT");
            return new ValidationResult(false, List.copyOf(issues));
        }
        String text = text(draft);
        if (text.isBlank()) issues.add("EMPTY_NARRATIVE");
        if (text.codePointCount(0, text.length()) > 12_000) issues.add("NARRATIVE_TOO_LONG");
        Matcher ids = EVIDENCE_ID.matcher(text);
        boolean hasReference = false;
        while (ids.find()) {
            hasReference = true;
            if (!evidence.evidenceCatalog().containsKey(ids.group(1))) issues.add("UNKNOWN_EVIDENCE");
        }
        if (!hasReference && !evidence.evidenceCatalog().isEmpty()) issues.add("MISSING_EVIDENCE_REFERENCE");
        String evidenceText = evidence.evidenceCatalog().values().stream().map(this::evidenceText).reduce("", (a, b) -> a + " " + b);
        Matcher numbers = NUMBER.matcher(text);
        while (numbers.find()) {
            String value = numbers.group();
            if (!evidenceText.contains(value)) issues.add("UNSUPPORTED_NUMBER");
        }
        if (TRADE.matcher(text).find()) issues.add("TRADE_INSTRUCTION");
        for (String conflict : evidence.conflicts()) {
            String key = conflict.length() > 8 ? conflict.substring(0, 8) : conflict;
            if (!text.contains(key)) issues.add("MISSING_CONFLICT");
        }
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues));
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

    public record ValidationResult(boolean valid, List<String> issues) {
        public ValidationResult {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }
}
