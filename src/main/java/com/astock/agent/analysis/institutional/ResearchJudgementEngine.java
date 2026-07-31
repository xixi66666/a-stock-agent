package com.astock.agent.analysis.institutional;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.Announcement;
import com.astock.agent.marketdata.model.CapitalData;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.FundFlow;
import com.astock.agent.marketdata.model.FundamentalData;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.ResearchItem;
import com.astock.agent.marketdata.model.SectionStatus;
import com.astock.agent.technical.IndicatorCard;
import com.astock.agent.technical.IndicatorState;
import com.astock.agent.technical.TechnicalSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 基于固定规则生成方向和证据边界；不做缺失维度的权重再分配。 */
public final class ResearchJudgementEngine {
    private static final Map<String, Integer> WEIGHTS = Map.of(
            "TECHNICAL_PRICE_VOLUME", 30,
            "FUND_FLOW_CAPITAL", 20,
            "EVENT_CATALYST", 20,
            "FUNDAMENTAL_EXPECTATION", 15,
            "VALUATION_INDUSTRY", 15);

    public DeterministicAssessment assess(StockResearchSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        List<String> missing = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<String> invalidation = new ArrayList<>();
        Map<String, EvidenceScore> dimensions = new LinkedHashMap<>();

        EvidenceScore technical = technical(snapshot, constraints, missing);
        EvidenceScore flow = flow(snapshot, missing);
        EvidenceScore events = events(snapshot, missing);
        EvidenceScore fundamentals = fundamentals(snapshot, missing, risks);
        EvidenceScore valuation = valuation(snapshot, fundamentals, missing, risks);
        dimensions.put(technical.dimension(), technical);
        dimensions.put(flow.dimension(), flow);
        dimensions.put(events.dimension(), events);
        dimensions.put(fundamentals.dimension(), fundamentals);
        dimensions.put(valuation.dimension(), valuation);

        if (technical.usable() && flow.usable()
                && technical.rawScore() >= 20 && flow.rawScore() <= -20) {
            conflicts.add("趋势证据偏强，但资金证据偏弱，二者存在方向冲突");
        }
        if (fundamentals.usable() && valuation.usable()
                && fundamentals.rawScore() < -20 && valuation.rawScore() > 20) {
            conflicts.add("估值相对便宜，但基本面证据偏弱，便宜不能单独构成正向判断");
        }
        if (technical.usable()) invalidation.add("趋势失效：SMA20跌破SMA60且20日收益转负");
        if (flow.usable()) invalidation.add("资金失效：20日主力资金由净流入转为持续净流出");
        if (events.usable()) invalidation.add("事件失效：已披露的正向事件被公告更正或取消");
        if (invalidation.isEmpty()) invalidation.add("补齐核心行情和K线后重新评估");

        int usable = (int) dimensions.values().stream().filter(EvidenceScore::usable).count();
        boolean core = usableSection(snapshot.quote()) && usableSection(snapshot.bars());
        EvidenceStatus evidenceStatus = !core || usable < 3 ? EvidenceStatus.INSUFFICIENT
                : usable < WEIGHTS.size() ? EvidenceStatus.PARTIAL : EvidenceStatus.SUFFICIENT;
        int score = dimensions.values().stream().mapToInt(EvidenceScore::weightedContribution).sum();
        Direction direction = evidenceStatus == EvidenceStatus.INSUFFICIENT ? Direction.INSUFFICIENT
                : score >= 20 ? Direction.STRONGER : score <= -20 ? Direction.WEAKER : Direction.NEUTRAL;
        List<ReportEvidence> drivers = dimensions.values().stream()
                .filter(EvidenceScore::usable)
                .sorted(Comparator.comparingInt(EvidenceScore::weightedContribution).reversed())
                .flatMap(d -> d.evidence().stream().limit(2))
                .limit(6).toList();
        if (drivers.isEmpty()) risks.add("当前没有足够的可引用证据支持方向判断");
        return new DeterministicAssessment(direction, evidenceStatus, dimensions, drivers,
                constraints, risks, conflicts, missing, invalidation, score);
    }

    private EvidenceScore technical(StockResearchSnapshot snapshot, List<String> constraints, List<String> missing) {
        String name = "TECHNICAL_PRICE_VOLUME";
        if (!usableSection(snapshot.quote()) || !usableSection(snapshot.bars())
                || !usableSection(snapshot.technical()) || !(snapshot.technical().payload().orElse(null) instanceof TechnicalSnapshot technical)) {
            missing.add("技术与量价数据");
            return empty(name, WEIGHTS.get(name));
        }
        Map<String, IndicatorCard> cards = technical.cards().stream().collect(java.util.stream.Collectors.toMap(
                IndicatorCard::id, c -> c, (a, b) -> a));
        int score = 0;
        int trend = signs(cards, List.of("SMA_20", "SMA_60", "SMA_120"));
        score += trend * 45;
        score += sign(cards.get("MACD_12_26_9")) * 20;
        score += signAverage(cards, List.of("RETURN_20", "RETURN_60")) * 20;
        score += sign(cards.get("VOLUME_RATIO_20")) * 15;
        IndicatorCard drawdown = cards.get("MAX_DRAWDOWN");
        if (drawdown != null && drawdown.value() != null && drawdown.value() <= -20) score -= 20;
        for (String id : List.of("RSI_6", "RSI_12", "KDJ_9_3_3")) {
            IndicatorCard card = cards.get(id);
            if (card != null && card.state() == IndicatorState.OVERBOUGHT) {
                constraints.add("动量指标超买，短期波动和回撤风险上升");
                break;
            }
        }
        return score(name, score, WEIGHTS.get(name), evidence("technical", "技术与量价", scoreText(score), snapshot.technical()));
    }

    private EvidenceScore flow(StockResearchSnapshot snapshot, List<String> missing) {
        String name = "FUND_FLOW_CAPITAL";
        Object payload = snapshot.fundFlow().payload().orElse(null);
        Object capitalPayload = snapshot.capital().payload().orElse(null);
        boolean hasFlows = usableSection(snapshot.fundFlow()) && payload instanceof List<?> values
                && values.stream().anyMatch(FundFlow.class::isInstance);
        boolean hasCapital = usableSection(snapshot.capital()) && capitalPayload instanceof CapitalData;
        if (!hasFlows && !hasCapital) {
            missing.add("资金流数据");
            return empty(name, WEIGHTS.get(name));
        }
        List<FundFlow> flows = hasFlows ? ((List<?>) payload).stream().filter(FundFlow.class::isInstance).map(FundFlow.class::cast).toList() : List.of();
        double sum5 = flows.stream().sorted(Comparator.comparing(FundFlow::date).reversed()).limit(5)
                .map(FundFlow::mainNetYuan).filter(v -> v != null).mapToDouble(BigDecimal::doubleValue).sum();
        double sum20 = flows.stream().sorted(Comparator.comparing(FundFlow::date).reversed()).limit(20)
                .map(FundFlow::mainNetYuan).filter(v -> v != null).mapToDouble(BigDecimal::doubleValue).sum();
        int score = hasFlows ? (int) Math.signum(sum5) * 35 + (int) Math.signum(sum20) * 65 : 0;
        if (hasCapital) {
            CapitalData capital = (CapitalData) capitalPayload;
            double dragon = capital.dragonTigerRecords().stream().map(CapitalData.DragonTigerRecord::netBuyYuan)
                    .filter(v -> v != null).mapToDouble(BigDecimal::doubleValue).sum();
            double premium = capital.blockTrades().stream().map(CapitalData.BlockTrade::premiumPercent)
                    .filter(v -> v != null).mapToDouble(BigDecimal::doubleValue).average().orElse(0);
            score += (int) Math.signum(dragon) * 10 + (int) Math.signum(premium) * 5;
        }
        DataSection<?> source = hasFlows ? snapshot.fundFlow() : snapshot.capital();
        return score(name, score, WEIGHTS.get(name), evidence("flow", "资金与筹码", "主力资金与结构化筹码共同判断", source));
    }

    private EvidenceScore events(StockResearchSnapshot snapshot, List<String> missing) {
        String name = "EVENT_CATALYST";
        Object payload = snapshot.announcements().payload().orElse(null);
        Object capitalPayload = snapshot.capital().payload().orElse(null);
        boolean hasAnnouncements = usableSection(snapshot.announcements()) && payload instanceof List<?> values
                && values.stream().anyMatch(Announcement.class::isInstance);
        boolean hasCapital = usableSection(snapshot.capital()) && capitalPayload instanceof CapitalData
                && hasStructuredCapital((CapitalData) capitalPayload);
        if (!hasAnnouncements && !hasCapital) {
            missing.add("公告与结构化事件");
            return empty(name, WEIGHTS.get(name));
        }
        List<Announcement> announcements = hasAnnouncements ? ((List<?>) payload).stream().filter(Announcement.class::isInstance).map(Announcement.class::cast).toList() : List.of();
        int score = 0;
        for (Announcement item : announcements) {
            String type = item.type() == null ? "" : item.type();
            if (containsAny(type, "预增", "增持", "回购", "分红", "中标")) score += 25;
            if (containsAny(type, "预减", "减持", "解禁", "诉讼", "违规", "亏损")) score -= 25;
        }
        if (hasCapital) {
            CapitalData capital = (CapitalData) capitalPayload;
            score += capital.unlocks().stream().map(CapitalData.UnlockRecord::totalShareRatio).filter(v -> v != null)
                    .mapToDouble(BigDecimal::doubleValue).anyMatch(v -> v > 5) ? -30 : 0;
            score += capital.dividends().stream().anyMatch(item -> item.cashPerShareYuan() != null && item.cashPerShareYuan().signum() > 0) ? 20 : 0;
        }
        DataSection<?> source = hasAnnouncements ? snapshot.announcements() : snapshot.capital();
        return score(name, clamp(score), WEIGHTS.get(name), evidence("events", "公告与结构化事件", "公告和资本事件共同判断", source));
    }

    private EvidenceScore fundamentals(StockResearchSnapshot snapshot, List<String> missing, List<String> risks) {
        String name = "FUNDAMENTAL_EXPECTATION";
        Object payload = snapshot.fundamentals().payload().orElse(null);
        boolean hasFundamental = usableSection(snapshot.fundamentals()) && payload instanceof FundamentalData;
        Object researchPayload = snapshot.research().payload().orElse(null);
        boolean hasResearch = usableSection(snapshot.research()) && researchPayload instanceof List<?>;
        if (!hasFundamental && !hasResearch) {
            missing.add("基本面与机构预期");
            return empty(name, WEIGHTS.get(name));
        }
        int score = 0;
        if (hasFundamental) {
            FundamentalData data = (FundamentalData) payload;
            List<BigDecimal> yoy = data.yearOverYearPercent().entrySet().stream()
                    .filter(e -> containsAny(e.getKey(), "收入", "营收", "利润", "净利", "盈利"))
                    .map(Map.Entry::getValue).filter(v -> v != null).toList();
            if (!yoy.isEmpty()) score += (int) Math.round(Math.signum(yoy.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0)) * 50);
        }
        if (hasResearch) {
            @SuppressWarnings("unchecked") List<ResearchItem> reports = ((List<?>) researchPayload).stream()
                    .filter(ResearchItem.class::isInstance).map(ResearchItem.class::cast).toList();
            ConsensusForecast forecast = new ConsensusForecastCalculator().calculate(reports);
            if (forecast.coverage() >= 2 && forecast.currentYearEpsMedian() != null && forecast.nextYearEpsMedian() != null) {
                score += forecast.nextYearEpsMedian().compareTo(forecast.currentYearEpsMedian()) >= 0 ? 50 : -50;
            } else if (!reports.isEmpty()) {
                risks.add("机构覆盖不足两家，EPS预期不形成方向信号");
            }
        }
        return score(name, clamp(score), WEIGHTS.get(name), evidence("fundamentals", "基本面与预期", scoreText(score), hasFundamental ? snapshot.fundamentals() : snapshot.research()));
    }

    private EvidenceScore valuation(StockResearchSnapshot snapshot, EvidenceScore fundamentals,
            List<String> missing, List<String> risks) {
        String name = "VALUATION_INDUSTRY";
        if (!usableSection(snapshot.industryValuation()) || !(snapshot.industryValuation().payload().orElse(null) instanceof IndustryValuationData data)
                || !usableSection(snapshot.quote())) {
            missing.add("估值与行业比较");
            return empty(name, WEIGHTS.get(name));
        }
        var quote = snapshot.quote().payload().orElseThrow();
        int score = 0;
        if (quote.peTtm() != null && data.peMedian() != null) score += quote.peTtm().compareTo(data.peMedian()) <= 0 ? 50 : -50;
        if (quote.pb() != null && data.pbMedian() != null) score += quote.pb().compareTo(data.pbMedian()) <= 0 ? 30 : -30;
        if (data.pePercentile() != null && data.pePercentile().compareTo(BigDecimal.valueOf(75)) > 0) score -= 20;
        if (fundamentals != null && fundamentals.usable() && fundamentals.rawScore() < -20 && score > 0) {
            risks.add("盈利指标恶化时，低估值不单独形成正向判断");
            score = 0;
        }
        return score(name, clamp(score), WEIGHTS.get(name), evidence("valuation", "行业估值", "PE/PB与行业中位数比较", snapshot.industryValuation()));
    }

    private static boolean hasStructuredCapital(CapitalData data) {
        return !data.marginHistory().isEmpty() || !data.blockTrades().isEmpty() || !data.shareholderChanges().isEmpty()
                || !data.unlocks().isEmpty() || !data.dividends().isEmpty() || !data.dragonTigerRecords().isEmpty();
    }

    private static EvidenceScore empty(String dimension, int weight) { return new EvidenceScore(dimension, 0, weight, 0, false, List.of()); }

    private static EvidenceScore score(String dimension, int raw, int weight, List<ReportEvidence> evidence) {
        int bounded = clamp(raw);
        return new EvidenceScore(dimension, bounded, weight, Math.round(bounded * weight / 100f), true, evidence);
    }

    private static List<ReportEvidence> evidence(String id, String title, String interpretation, DataSection<?> section) {
        if (section == null || section.provenance().isEmpty()) return List.of();
        Provenance p = section.provenance().orElseThrow();
        String source = p.provider() + "|" + p.sourceUrl();
        Instant observed = p.providerTimestamp() == null ? p.fetchedAt() : p.providerTimestamp();
        return List.of(new ReportEvidence(id, title, interpretation, id, source, observed));
    }

    private static boolean usableSection(DataSection<?> section) {
        return section != null && section.status() != SectionStatus.UNAVAILABLE && section.payload().isPresent();
    }

    private static int signs(Map<String, IndicatorCard> cards, List<String> ids) {
        int present = 0, sum = 0;
        for (String id : ids) { if (cards.containsKey(id)) { present++; sum += sign(cards.get(id)); } }
        return present == 0 ? 0 : (int) Math.signum(sum);
    }

    private static int signAverage(Map<String, IndicatorCard> cards, List<String> ids) { return signs(cards, ids); }
    private static int sign(IndicatorCard c) {
        if (c == null || c.value() == null) return 0;
        return c.state() == IndicatorState.STRONG || c.value() > 0 ? 1 : c.state() == IndicatorState.WEAK || c.value() < 0 ? -1 : 0;
    }
    private static int clamp(int value) { return Math.max(-100, Math.min(100, value)); }
    private static String scoreText(int score) { return "规则方向分" + score; }
    private static boolean containsAny(String value, String... terms) { for (String t : terms) if (value.contains(t)) return true; return false; }
    private static String format(double value) { return String.format(Locale.ROOT, "%.0f", value); }
}
