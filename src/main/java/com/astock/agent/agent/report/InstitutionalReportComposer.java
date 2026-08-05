package com.astock.agent.agent.report;

import com.astock.agent.agent.SourceCitation;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.analysis.institutional.ConsensusForecast;
import com.astock.agent.analysis.institutional.AnalysisModule;
import com.astock.agent.analysis.institutional.AnalysisSignal;
import com.astock.agent.analysis.institutional.DeterministicAssessment;
import com.astock.agent.analysis.institutional.Direction;
import com.astock.agent.analysis.institutional.EvidenceScore;
import com.astock.agent.analysis.institutional.ReportEvidence;
import com.astock.agent.analysis.institutional.ModuleAnalysis;
import com.astock.agent.marketdata.model.Announcement;
import com.astock.agent.marketdata.model.CapitalData;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.FundFlowSummary;
import com.astock.agent.marketdata.model.FundFlowWindowSummary;
import com.astock.agent.marketdata.model.FundamentalData;
import com.astock.agent.marketdata.model.IndustryPeerComparison;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.PeerSelectionReason;
import com.astock.agent.marketdata.model.NewsItem;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.SectionStatus;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.ResearchItem;
import com.astock.agent.technical.IndicatorCard;
import com.astock.agent.technical.TechnicalSnapshot;
import java.math.BigDecimal;
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
        addMarketEvidence(catalog, snapshot);
        assessment.moduleAnalyses().values().forEach(module -> addModuleEvidence(catalog, module));
        List<ReportEvidence> news = boundedNews(snapshot, catalog);
        List<ReportEvidence> announcements = boundedAnnouncements(snapshot, catalog);
        String name = snapshot.quote().payload().map(q -> q.name()).orElse("");
        String code = snapshot.security().code();
        return new ReportEvidencePackage(code, name, HORIZON, assessment.direction(), assessment.evidenceStatus(), catalog,
                assessment.constraints(), assessment.risks(), assessment.conflicts(), assessment.missingData(),
                assessment.invalidationConditions(), news, announcements, snapshot.fetchedAt(), RULE_VERSION,
                assessment.moduleAnalyses(), assessment.coreDrivers());
    }

    public InstitutionalResearchReport fallback(StockResearchSnapshot snapshot, DeterministicAssessment assessment, String reason) {
        return fallbackWithDiagnostic(snapshot, assessment, null, reason);
    }

    public InstitutionalResearchReport fallbackWithDiagnostic(StockResearchSnapshot snapshot,
            DeterministicAssessment assessment, ModelDiagnostic diagnostic) {
        String reason = diagnostic == null ? null : diagnostic.errorCode() + "：" + diagnostic.message();
        return fallbackWithDiagnostic(snapshot, assessment, diagnostic, reason);
    }

    private InstitutionalResearchReport fallbackWithDiagnostic(StockResearchSnapshot snapshot,
            DeterministicAssessment assessment, ModelDiagnostic diagnostic, String reason) {
        ReportEvidencePackage evidence = compose(snapshot, assessment);
        boolean coreAvailable = usable(snapshot.quote()) && usable(snapshot.bars());
        GenerationMode mode = coreAvailable ? GenerationMode.DETERMINISTIC_FALLBACK : GenerationMode.REPORT_UNAVAILABLE;
        String summary = deterministicSummary(snapshot, assessment, reason);
        return report(snapshot, assessment, summary,
                technicalNarrative(assessment), fundamentalNarrative(assessment), valuationNarrative(assessment),
                technicalFacts(snapshot), fundamentalFacts(snapshot), valuationFacts(snapshot),
                events(snapshot, evidence), mode, null, diagnostic);
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
                technicalFacts(snapshot), fundamentalFacts(snapshot), valuationFacts(snapshot),
                events(snapshot, evidence), GenerationMode.MODEL_ASSISTED, modelName, null);
    }

    public InstitutionalResearchReport assembleValidated(StockResearchSnapshot snapshot,
            DeterministicAssessment assessment, ReportNarrativeDraft draft,
            ReportValidator.ValidationResult validation, String modelName, ModelDiagnostic diagnostic) {
        if (draft == null) return fallbackWithDiagnostic(snapshot, assessment, diagnostic);
        ReportEvidencePackage evidence = compose(snapshot, assessment);
        boolean partial = validation != null && !validation.blockingIssues().isEmpty();
        boolean warning = validation != null && validation.hasWarnings();
        GenerationMode mode = partial ? GenerationMode.MODEL_ASSISTED_PARTIAL
                : warning ? GenerationMode.MODEL_ASSISTED_WITH_WARNINGS : GenerationMode.MODEL_ASSISTED;
        String summaryFallback = deterministicSummary(snapshot, assessment, null);
        String technicalFallback = technicalNarrative(assessment);
        String fundamentalFallback = fundamentalNarrative(assessment);
        String valuationFallback = valuationNarrative(assessment);
        return report(snapshot, assessment,
                narrative(draft.executiveSummary(), summaryFallback, blocks(validation, ReportValidator.FIELD_EXECUTIVE_SUMMARY)),
                narrative(draft.technicalAndFlowNarrative(), technicalFallback, blocks(validation, ReportValidator.FIELD_TECHNICAL_AND_FLOW)),
                narrative(draft.fundamentalNarrative(), fundamentalFallback, blocks(validation, ReportValidator.FIELD_FUNDAMENTALS)),
                narrative(draft.valuationAndIndustryNarrative(), valuationFallback, blocks(validation, ReportValidator.FIELD_VALUATION_AND_INDUSTRY)),
                technicalFacts(snapshot), fundamentalFacts(snapshot), valuationFacts(snapshot),
                events(snapshot, evidence), mode, modelName, diagnostic);
    }

    private static String narrative(String modelText, String fallback, boolean blocked) {
        return blocked ? fallback : nonBlank(modelText, fallback);
    }

    private static boolean blocks(ReportValidator.ValidationResult validation, String field) {
        return validation != null && validation.blocksField(field);
    }

    private InstitutionalResearchReport report(StockResearchSnapshot snapshot, DeterministicAssessment assessment,
            String summary, String technical, String fundamental, String valuation,
            List<ReportFact> technicalFacts, List<ReportFact> fundamentalFacts, List<ReportFact> valuationFacts,
            List<ReportEvent> events, GenerationMode mode, String modelName, ModelDiagnostic diagnostic) {
        ModuleAnalysis technicalModule = assessment.moduleAnalysis(AnalysisModule.TECHNICAL_PRICE_VOLUME);
        ModuleAnalysis flowModule = assessment.moduleAnalysis(AnalysisModule.FUND_FLOW_CAPITAL);
        ModuleAnalysis fundamentalModule = assessment.moduleAnalysis(AnalysisModule.FUNDAMENTAL_EXPECTATION);
        ModuleAnalysis valuationModule = assessment.moduleAnalysis(AnalysisModule.VALUATION_INDUSTRY);
        return new InstitutionalResearchReport(assessment.direction(), HORIZON, assessment.evidenceStatus(), summary,
                assessment.coreDrivers(), new TechnicalAndFlowAnalysis(technical, List.of(),
                        mergeFacts(technicalFacts, facts(technicalModule), facts(flowModule)),
                        mergeSignals(technicalModule, flowModule), mergeText(technicalModule, flowModule, ModuleAnalysis::methodology),
                        mergeText(technicalModule, flowModule, ModuleAnalysis::counterEvidence),
                        mergeText(technicalModule, flowModule, ModuleAnalysis::limitations),
                        snapshot.fundFlowSummary()),
                new FundamentalExpectationAnalysis(fundamental, List.of(), mergeFacts(fundamentalFacts, facts(fundamentalModule)),
                        signals(fundamentalModule), text(fundamentalModule, ModuleAnalysis::methodology),
                        text(fundamentalModule, ModuleAnalysis::counterEvidence), text(fundamentalModule, ModuleAnalysis::limitations)),
                new ValuationIndustryAnalysis(valuation, List.of(), mergeFacts(valuationFacts, facts(valuationModule)),
                        signals(valuationModule), text(valuationModule, ModuleAnalysis::methodology),
                        text(valuationModule, ModuleAnalysis::counterEvidence),
                        text(valuationModule, ModuleAnalysis::limitations), snapshot.industryValuation()),
                events, assessment.risks(), assessment.conflicts(),
                assessment.missingData(), assessment.invalidationConditions(), citations(snapshot), mode,
                RULE_VERSION, PROMPT_VERSION, modelName, snapshot.fetchedAt(), Instant.now(), "", diagnostic);
    }

    @SafeVarargs
    private static List<ReportFact> mergeFacts(List<ReportFact>... groups) {
        Map<String, ReportFact> result = new LinkedHashMap<>();
        for (List<ReportFact> group : groups) if (group != null) group.forEach(fact -> result.putIfAbsent(fact.id(), fact));
        return List.copyOf(result.values());
    }

    private static List<ReportFact> facts(ModuleAnalysis module) { return module == null ? List.of() : module.facts(); }
    private static List<AnalysisSignal> signals(ModuleAnalysis module) { return module == null ? List.of() : module.signals(); }
    private static List<AnalysisSignal> mergeSignals(ModuleAnalysis... modules) {
        return java.util.Arrays.stream(modules).filter(java.util.Objects::nonNull)
                .flatMap(module -> module.signals().stream()).toList();
    }
    private static List<String> text(ModuleAnalysis module,
            java.util.function.Function<ModuleAnalysis, List<String>> getter) {
        return module == null ? List.of() : getter.apply(module);
    }
    private static List<String> mergeText(ModuleAnalysis left, ModuleAnalysis right,
            java.util.function.Function<ModuleAnalysis, List<String>> getter) {
        return java.util.stream.Stream.of(left, right).filter(java.util.Objects::nonNull)
                .flatMap(module -> getter.apply(module).stream()).distinct().toList();
    }

    private static List<ReportFact> technicalFacts(StockResearchSnapshot snapshot) {
        List<ReportFact> facts = new ArrayList<>();
        snapshot.quote().payload().ifPresent(quote -> {
            add(facts, "最新价", quote.price(), "quote");
            add(facts, "涨跌幅", quote.changePercent(), "%", "quote");
            add(facts, "换手率", quote.turnoverPercent(), "%", "quote");
        });
        snapshot.bars().payload().ifPresent(bars -> {
            if (!bars.isEmpty()) {
                add(facts, "K线样本", bars.size() + " 根", "bars");
                add(facts, "最新收盘", bars.getLast().close(), "bars");
            }
        });
        Object payload = snapshot.technical().payload().orElse(null);
        if (payload instanceof TechnicalSnapshot technical) {
            Map<String, IndicatorCard> cards = technical.cards().stream()
                    .collect(java.util.stream.Collectors.toMap(IndicatorCard::id, card -> card, (a, b) -> a));
            for (String id : List.of("SMA_20", "SMA_60", "MACD_12_26_9", "RSI_6", "VOLUME_RATIO_20", "RETURN_20")) {
                IndicatorCard card = cards.get(id);
                if (card != null && card.value() != null) add(facts, card.name(), card.value(), card.unit(), "technical");
            }
        }
        addFlowFacts(facts, snapshot);
        addCapitalFacts(facts, snapshot);
        return List.copyOf(facts);
    }

    private static List<ReportFact> fundamentalFacts(StockResearchSnapshot snapshot) {
        List<ReportFact> facts = new ArrayList<>();
        Object payload = snapshot.fundamentals().payload().orElse(null);
        if (payload instanceof FundamentalData data) {
            data.yearOverYearPercent().entrySet().stream().limit(6)
                    .forEach(entry -> add(facts, entry.getKey() + "同比", entry.getValue(), "%", "fundamentals"));
            data.metrics().entrySet().stream().limit(4)
                    .forEach(entry -> add(facts, entry.getKey(), entry.getValue(), "fundamentals"));
            add(facts, "报告期", data.reportPeriod(), "fundamentals");
        }
        Object research = snapshot.research().payload().orElse(null);
        if (research instanceof List<?> values) {
            List<ResearchItem> reports = values.stream().filter(ResearchItem.class::isInstance)
                    .map(ResearchItem.class::cast).toList();
            ConsensusForecast forecast = new com.astock.agent.analysis.institutional.ConsensusForecastCalculator().calculate(reports);
            add(facts, "机构覆盖数", forecast.coverage() + " 家", "research");
            add(facts, "当年EPS中位数", forecast.currentYearEpsMedian(), "research");
            add(facts, "次年EPS中位数", forecast.nextYearEpsMedian(), "research");
        }
        return List.copyOf(facts);
    }

    private static List<ReportFact> valuationFacts(StockResearchSnapshot snapshot) {
        List<ReportFact> facts = new ArrayList<>();
        snapshot.quote().payload().ifPresent(quote -> {
            add(facts, "个股PE(TTM)", quote.peTtm(), "quote");
            add(facts, "个股PB", quote.pb(), "quote");
        });
        Object payload = snapshot.industryValuation().payload().orElse(null);
        if (payload instanceof IndustryValuationData data) {
            add(facts, "行业名称", data.industryName(), "industryValuation");
            add(facts, "行业PE中位数", data.peMedian(), "industryValuation");
            add(facts, "行业PE分位数", data.pePercentile(), "%", "industryValuation");
            add(facts, "行业PB中位数", data.pbMedian(), "industryValuation");
            add(facts, "估值样本数", data.totalSamples() + " 家", "industryValuation");
            data.selectedPeers().forEach(peer -> addPeerFact(facts, peer));
        }
        return List.copyOf(facts);
    }

    private static void addFlowFacts(List<ReportFact> facts, StockResearchSnapshot snapshot) {
        FundFlowSummary summary = snapshot.fundFlowSummary().payload().orElse(null);
        if (summary == null) return;
        addFlowWindowFacts(facts, 5, summary.fiveDay());
        addFlowWindowFacts(facts, 20, summary.twentyDay());
    }

    private static void addFlowWindowFacts(
            List<ReportFact> facts, int days, FundFlowWindowSummary window) {
        if (window == null) return;
        add(facts, "近" + days + "日主力净流入", window.mainNetYuan(), "元", "fundFlowSummary");
        add(facts, "近" + days + "日超大单净流入", window.superLargeNetYuan(), "元", "fundFlowSummary");
        add(facts, "近" + days + "日大单净流入", window.largeNetYuan(), "元", "fundFlowSummary");
        add(facts, "近" + days + "日中单净流入", window.mediumNetYuan(), "元", "fundFlowSummary");
        add(facts, "近" + days + "日小单净流入", window.smallNetYuan(), "元", "fundFlowSummary");
    }

    private static void addPeerFact(List<ReportFact> facts, IndustryPeerComparison peer) {
        if (peer == null) return;
        List<String> values = new ArrayList<>();
        addSegment(values, "PE", peer.peDynamic(), "");
        addSegment(values, "PB", peer.pb(), "");
        addSegment(values, "总市值", peer.totalMarketValueYuan(), " 元");
        peer.selectionReasons().stream().map(InstitutionalReportComposer::reasonLabel).forEach(values::add);
        if (values.isEmpty()) return;
        String name = nonBlank(peer.name(), "未知同行");
        String code = nonBlank(peer.code(), "------");
        add(facts, "同行估值·" + name + "(" + code + ")", String.join("；", values), "industryValuation");
    }

    private static void addSegment(List<String> values, String label, BigDecimal value, String suffix) {
        if (value != null) values.add(label + " " + value.stripTrailingZeros().toPlainString() + suffix);
    }

    private static String reasonLabel(PeerSelectionReason reason) {
        return switch (reason) {
            case MARKET_CAP_NEARBY -> "市值接近";
            case INDUSTRY_LEADER -> "行业龙头";
        };
    }

    private static void addCapitalFacts(List<ReportFact> facts, StockResearchSnapshot snapshot) {
        Object payload = snapshot.capital().payload().orElse(null);
        if (payload instanceof CapitalData data) {
            add(facts, "融资记录数", data.marginHistory().size() + " 条", "capital");
            add(facts, "大宗交易数", data.blockTrades().size() + " 条", "capital");
            add(facts, "解禁记录数", data.unlocks().size() + " 条", "capital");
            add(facts, "分红记录数", data.dividends().size() + " 条", "capital");
        }
    }

    private static void add(List<ReportFact> facts, String label, Object value, String sourceId) {
        add(facts, label, value, "", sourceId);
    }

    private static void add(List<ReportFact> facts, String label, Object value, String unit, String sourceId) {
        if (value == null) return;
        String text = value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString() : String.valueOf(value);
        if (unit != null && !unit.isBlank() && !text.endsWith(unit)) text += unit;
        if (!text.isBlank()) facts.add(new ReportFact(label, text, sourceId));
    }

    private static String deterministicSummary(StockResearchSnapshot snapshot, DeterministicAssessment assessment, String reason) {
        String subject = snapshot.quote().payload().map(q -> q.name() + "（" + q.security().code() + "）").orElse(snapshot.security().code());
        String relation = assessment.conflicts().isEmpty() ? "主要证据未发现明确冲突" : "主要证据之间存在冲突，需重点跟踪";
        String limitation = assessment.evidenceStatus().name().equals("INSUFFICIENT") ? "现有证据不足以形成方向判断" : "结论仅适用于未来1-3个月的研究观察";
        String fallback = reason == null || reason.isBlank() ? "" : "模型叙述不可用（" + clip(reason, 120) + "），已使用确定性规则。";
        return subject + "的研究结论为“" + assessment.direction().label() + "”。" + limitation + "；" + relation + "。" + fallback;
    }

    private static String technicalNarrative(DeterministicAssessment assessment) {
        ModuleAnalysis technical = assessment.moduleAnalysis(AnalysisModule.TECHNICAL_PRICE_VOLUME);
        ModuleAnalysis flow = assessment.moduleAnalysis(AnalysisModule.FUND_FLOW_CAPITAL);
        if (technical != null) {
            String flowText = flow == null ? "" : "；" + flow.conclusion();
            return technical.conclusion() + flowText + "。方法依据：" + String.join("；", technical.methodology());
        }
        EvidenceScore t = score(assessment, "TECHNICAL_PRICE_VOLUME");
        EvidenceScore f = score(assessment, "FUND_FLOW_CAPITAL");
        if (t == null || !t.usable()) return "技术证据不可用，现有数据无法判断趋势与量价关系。";
        if (f != null && f.usable() && Integer.signum(t.rawScore()) == -Integer.signum(f.rawScore()) && t.rawScore() != 0 && f.rawScore() != 0) {
            return "技术与量价偏向" + (t.rawScore() > 0 ? "偏强" : "偏弱") + "，但资金维度方向相反，形成显式冲突。";
        }
        return "技术与量价指标整体" + (t.rawScore() > 20 ? "偏强" : t.rawScore() < -20 ? "偏弱" : "中性") + "，结论由趋势、动量和成交量共同决定。";
    }

    private static String fundamentalNarrative(DeterministicAssessment assessment) {
        ModuleAnalysis module = assessment.moduleAnalysis(AnalysisModule.FUNDAMENTAL_EXPECTATION);
        if (module != null) return module.conclusion() + "。方法依据：" + String.join("；", module.methodology());
        EvidenceScore score = score(assessment, "FUNDAMENTAL_EXPECTATION");
        return score == null || !score.usable() ? "基本面与机构预期证据不足，无法判断盈利趋势。"
                : "基本面与机构预期" + (score.rawScore() > 20 ? "改善" : score.rawScore() < -20 ? "承压" : "分化") + "，机构覆盖不足时不放大EPS信号。";
    }

    private static String valuationNarrative(DeterministicAssessment assessment) {
        ModuleAnalysis module = assessment.moduleAnalysis(AnalysisModule.VALUATION_INDUSTRY);
        if (module != null) return module.conclusion() + "。方法依据：" + String.join("；", module.methodology());
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

    private static void addMarketEvidence(Map<String, ReportEvidence> catalog, StockResearchSnapshot snapshot) {
        snapshot.quote().payload().ifPresent(quote -> catalog.put("quote-price",
                itemEvidence("quote-price", "最新价", quote.price().stripTrailingZeros().toPlainString(), "quote", snapshot.quote())));
        snapshot.bars().payload().ifPresent(bars -> {
            if (!bars.isEmpty()) catalog.put("bars-history", itemEvidence("bars-history", "K线历史",
                    "样本" + bars.size() + "根，最新收盘" + bars.getLast().close().stripTrailingZeros().toPlainString(),
                    "bars", snapshot.bars()));
        });
    }

    private static void addModuleEvidence(Map<String, ReportEvidence> catalog, ModuleAnalysis module) {
        for (ReportFact fact : module.facts()) {
            catalog.putIfAbsent(fact.id(), new ReportEvidence(fact.id(), fact.label(), fact.value(),
                    fact.sourceId(), fact.sourceId(), fact.observedAt()));
        }
        for (AnalysisSignal signal : module.signals()) {
            catalog.putIfAbsent(signal.id(), new ReportEvidence(signal.id(), signal.conclusion(), signal.rationale(),
                    module.module().name(), String.join(",", module.sourceIds()), null));
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
        sections.put("fundFlow", snapshot.fundFlow()); sections.put("fundFlowSummary", snapshot.fundFlowSummary());
        sections.put("capital", snapshot.capital()); sections.put("fundamentals", snapshot.fundamentals());
        sections.put("research", snapshot.research()); sections.put("news", snapshot.news()); sections.put("announcements", snapshot.announcements());
        sections.put("industryValuation", snapshot.industryValuation()); return sections;
    }

    private static boolean usable(DataSection<?> section) { return section != null && section.status() != SectionStatus.UNAVAILABLE && section.payload().isPresent(); }
    private static String nonBlank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String clip(String value, int max) { if (value == null) return ""; return value.codePointCount(0, value.length()) <= max ? value : value.substring(0, value.offsetByCodePoints(0, max)); }
}
