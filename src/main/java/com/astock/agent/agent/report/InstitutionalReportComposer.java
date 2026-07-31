package com.astock.agent.agent.report;

import com.astock.agent.agent.SourceCitation;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.analysis.institutional.DeterministicAssessment;
import com.astock.agent.analysis.institutional.Direction;
import com.astock.agent.analysis.institutional.EvidenceScore;
import com.astock.agent.analysis.institutional.ReportEvidence;
import com.astock.agent.marketdata.model.Announcement;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.NewsItem;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.SectionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将快照压缩成有界证据，并把方向等关键字段固定在 Java 侧。 */
public final class InstitutionalReportComposer {
    public static final String HORIZON = "1-3个月";
    public static final String RULE_VERSION = "institutional-rules-v1";
    public static final String PROMPT_VERSION = "institutional-narrative-v1";

    public ReportEvidencePackage compose(StockResearchSnapshot snapshot, DeterministicAssessment assessment) {
        if (snapshot == null || assessment == null) throw new IllegalArgumentException("snapshot and assessment are required");
        Map<String, ReportEvidence> catalog = new LinkedHashMap<>();
        assessment.coreDrivers().forEach(e -> catalog.putIfAbsent(e.id(), e));
        addSectionEvidence(catalog, "quote", "行情", snapshot.quote());
        addSectionEvidence(catalog, "bars", "K线", snapshot.bars());
        addSectionEvidence(catalog, "technical", "技术", snapshot.technical());
        addSectionEvidence(catalog, "flow", "资金", snapshot.fundFlow());
        addSectionEvidence(catalog, "fundamentals", "基本面", snapshot.fundamentals());
        addSectionEvidence(catalog, "valuation", "行业估值", snapshot.industryValuation());
        List<ReportEvidence> news = boundedNews(snapshot, catalog);
        List<ReportEvidence> announcements = boundedAnnouncements(snapshot, catalog);
        String name = snapshot.quote().payload().map(q -> q.name()).orElse("");
        String code = snapshot.security().code();
        return new ReportEvidencePackage(code, name, HORIZON, assessment.direction(), assessment.evidenceStatus(), catalog,
                assessment.constraints(), assessment.risks(), assessment.conflicts(), assessment.missingData(),
                assessment.invalidationConditions(), news, announcements, snapshot.fetchedAt(), RULE_VERSION);
    }

    public InstitutionalResearchReport fallback(StockResearchSnapshot snapshot, DeterministicAssessment assessment, String reason) {
        ReportEvidencePackage evidence = compose(snapshot, assessment);
        boolean coreAvailable = usable(snapshot.quote()) && usable(snapshot.bars());
        GenerationMode mode = coreAvailable ? GenerationMode.DETERMINISTIC_FALLBACK : GenerationMode.REPORT_UNAVAILABLE;
        String summary = deterministicSummary(snapshot, assessment, reason);
        return report(snapshot, assessment, summary,
                technicalNarrative(assessment), fundamentalNarrative(assessment), valuationNarrative(assessment),
                events(snapshot, evidence), mode, null);
    }

    public InstitutionalResearchReport assemble(StockResearchSnapshot snapshot, DeterministicAssessment assessment,
            ReportNarrativeDraft draft, String modelName) {
        if (draft == null) return fallback(snapshot, assessment, "model returned no narrative");
        ReportEvidencePackage evidence = compose(snapshot, assessment);
        return report(snapshot, assessment,
                nonBlank(draft.executiveSummary(), deterministicSummary(snapshot, assessment, null)),
                nonBlank(draft.technicalAndFlowNarrative(), technicalNarrative(assessment)),
                nonBlank(draft.fundamentalNarrative(), fundamentalNarrative(assessment)),
                nonBlank(draft.valuationAndIndustryNarrative(), valuationNarrative(assessment)),
                events(snapshot, evidence), GenerationMode.MODEL_ASSISTED, modelName);
    }

    private InstitutionalResearchReport report(StockResearchSnapshot snapshot, DeterministicAssessment assessment,
            String summary, String technical, String fundamental, String valuation, List<ReportEvent> events,
            GenerationMode mode, String modelName) {
        return new InstitutionalResearchReport(assessment.direction(), HORIZON, assessment.evidenceStatus(), summary,
                assessment.coreDrivers(), new TechnicalAndFlowAnalysis(technical, List.of()),
                new FundamentalExpectationAnalysis(fundamental, List.of()),
                new ValuationIndustryAnalysis(valuation, List.of()), events, assessment.risks(), assessment.conflicts(),
                assessment.missingData(), assessment.invalidationConditions(), citations(snapshot), mode,
                RULE_VERSION, PROMPT_VERSION, modelName, snapshot.fetchedAt(), Instant.now(), "");
    }

    private static String deterministicSummary(StockResearchSnapshot snapshot, DeterministicAssessment assessment, String reason) {
        String subject = snapshot.quote().payload().map(q -> q.name() + "（" + q.security().code() + "）").orElse(snapshot.security().code());
        String relation = assessment.conflicts().isEmpty() ? "主要证据未发现明确冲突" : "主要证据之间存在冲突，需重点跟踪";
        String limitation = assessment.evidenceStatus().name().equals("INSUFFICIENT") ? "现有证据不足以形成方向判断" : "结论仅适用于未来1-3个月的研究观察";
        String fallback = reason == null || reason.isBlank() ? "" : "模型叙述不可用（" + clip(reason, 120) + "），已使用确定性规则。";
        return subject + "的研究结论为“" + assessment.direction().label() + "”。" + limitation + "；" + relation + "。" + fallback;
    }

    private static String technicalNarrative(DeterministicAssessment assessment) {
        EvidenceScore t = score(assessment, "TECHNICAL_PRICE_VOLUME");
        EvidenceScore f = score(assessment, "FUND_FLOW_CAPITAL");
        if (t == null || !t.usable()) return "技术证据不可用，现有数据无法判断趋势与量价关系。";
        if (f != null && f.usable() && Integer.signum(t.rawScore()) == -Integer.signum(f.rawScore()) && t.rawScore() != 0 && f.rawScore() != 0) {
            return "技术与量价偏向" + (t.rawScore() > 0 ? "偏强" : "偏弱") + "，但资金维度方向相反，形成显式冲突。";
        }
        return "技术与量价指标整体" + (t.rawScore() > 20 ? "偏强" : t.rawScore() < -20 ? "偏弱" : "中性") + "，结论由趋势、动量和成交量共同决定。";
    }

    private static String fundamentalNarrative(DeterministicAssessment assessment) {
        EvidenceScore score = score(assessment, "FUNDAMENTAL_EXPECTATION");
        return score == null || !score.usable() ? "基本面与机构预期证据不足，无法判断盈利趋势。"
                : "基本面与机构预期" + (score.rawScore() > 20 ? "改善" : score.rawScore() < -20 ? "承压" : "分化") + "，机构覆盖不足时不放大EPS信号。";
    }

    private static String valuationNarrative(DeterministicAssessment assessment) {
        EvidenceScore score = score(assessment, "VALUATION_INDUSTRY");
        return score == null || !score.usable() ? "行业估值证据不可用，无法判断个股相对行业的位置。"
                : "个股PE/PB与行业样本比较后显示估值" + (score.rawScore() > 20 ? "相对有利" : score.rawScore() < -20 ? "相对偏高" : "处于中性区间") + "；估值结论需结合盈利变化理解。";
    }

    private static List<ReportEvent> events(StockResearchSnapshot snapshot, ReportEvidencePackage evidence) {
        return evidence.announcements().stream().map(item -> new ReportEvent(item.title(), item.interpretation(), item.sourceId(), item.observedAt())).toList();
    }

    private static List<ReportEvidence> boundedNews(StockResearchSnapshot snapshot, Map<String, ReportEvidence> catalog) {
        Object payload = snapshot.news().payload().orElse(null);
        if (!(payload instanceof List<?> values)) return List.of();
        List<ReportEvidence> result = new ArrayList<>(); int index = 0;
        for (Object value : values) {
            if (!(value instanceof NewsItem item) || index >= 20) break;
            String id = "news-" + (++index);
            ReportEvidence evidence = itemEvidence(id, "新闻：" + clip(item.title(), 180), clip(item.summary(), 1000), "news", snapshot.news());
            catalog.putIfAbsent(id, evidence); result.add(evidence);
        }
        return List.copyOf(result);
    }

    private static List<ReportEvidence> boundedAnnouncements(StockResearchSnapshot snapshot, Map<String, ReportEvidence> catalog) {
        Object payload = snapshot.announcements().payload().orElse(null);
        if (!(payload instanceof List<?> values)) return List.of();
        List<ReportEvidence> result = new ArrayList<>(); int index = 0;
        for (Object value : values) {
            if (!(value instanceof Announcement item) || index >= 20) break;
            String id = "announcement-" + (++index);
            ReportEvidence evidence = itemEvidence(id, "公告：" + clip(item.title(), 180), clip(item.type(), 1000), "announcements", snapshot.announcements());
            catalog.putIfAbsent(id, evidence); result.add(evidence);
        }
        return List.copyOf(result);
    }

    private static void addSectionEvidence(Map<String, ReportEvidence> catalog, String id, String title, DataSection<?> section) {
        if (usable(section) && section.provenance().isPresent()) {
            catalog.putIfAbsent(id, itemEvidence(id, title, "该分区已提供可追溯数据。", id, section));
        }
    }

    private static ReportEvidence itemEvidence(String id, String title, String interpretation, String section, DataSection<?> data) {
        Provenance p = data.provenance().orElse(null);
        return new ReportEvidence(id, nonBlank(title, section), nonBlank(interpretation, "可追溯数据"), section,
                p == null ? null : p.provider() + "|" + p.sourceUrl(), p == null ? null : (p.providerTimestamp() == null ? p.fetchedAt() : p.providerTimestamp()));
    }

    private static EvidenceScore score(DeterministicAssessment assessment, String name) {
        return assessment.evidenceScore(name);
    }

    private static List<SourceCitation> citations(StockResearchSnapshot snapshot) {
        List<SourceCitation> result = new ArrayList<>();
        sections(snapshot).forEach((name, section) -> section.provenance().ifPresent(p -> result.add(new SourceCitation(name, p.provider(), p.sourceUrl().toString(), p.fetchedAt(), section.status().name()))));
        return List.copyOf(result);
    }

    private static Map<String, DataSection<?>> sections(StockResearchSnapshot snapshot) {
        Map<String, DataSection<?>> sections = new LinkedHashMap<>();
        sections.put("quote", snapshot.quote()); sections.put("bars", snapshot.bars()); sections.put("technical", snapshot.technical());
        sections.put("fundFlow", snapshot.fundFlow()); sections.put("capital", snapshot.capital()); sections.put("fundamentals", snapshot.fundamentals());
        sections.put("research", snapshot.research()); sections.put("news", snapshot.news()); sections.put("announcements", snapshot.announcements());
        sections.put("industryValuation", snapshot.industryValuation()); return sections;
    }

    private static boolean usable(DataSection<?> section) { return section != null && section.status() != SectionStatus.UNAVAILABLE && section.payload().isPresent(); }
    private static String nonBlank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String clip(String value, int max) { if (value == null) return ""; return value.codePointCount(0, value.length()) <= max ? value : value.substring(0, value.offsetByCodePoints(0, max)); }
}
