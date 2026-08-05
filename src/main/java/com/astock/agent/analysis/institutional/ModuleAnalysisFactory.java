package com.astock.agent.analysis.institutional;

import com.astock.agent.agent.report.ReportFact;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.Announcement;
import com.astock.agent.marketdata.model.CapitalData;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.FundFlowSummary;
import com.astock.agent.marketdata.model.FundFlowWindowSummary;
import com.astock.agent.marketdata.model.FundamentalData;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.ResearchItem;
import com.astock.agent.technical.IndicatorCard;
import com.astock.agent.technical.TechnicalSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将维度分数展开为可审计的事实、信号、方法依据与限制。 */
/**
 * 把确定性维度分数转换成面向报告的模块分析。
 *
 * <p>分数适合机器汇总，模块分析还需要结论、方法依据、反证、限制、事实和信号，
 * 因此由本类把同一批证据组织成可阅读且可追溯的报告结构。</p>
 */
public final class ModuleAnalysisFactory {

    public Map<AnalysisModule, ModuleAnalysis> analyze(
            StockResearchSnapshot snapshot, Map<String, EvidenceScore> dimensions) {
        // 每个模块独立生成；不可用模块保留限制说明，而不是用空字符串伪装成健康。
        EnumMap<AnalysisModule, ModuleAnalysis> result = new EnumMap<>(AnalysisModule.class);
        result.put(AnalysisModule.TECHNICAL_PRICE_VOLUME, technical(snapshot, score(dimensions, AnalysisModule.TECHNICAL_PRICE_VOLUME)));
        result.put(AnalysisModule.FUND_FLOW_CAPITAL, flowAndCapital(snapshot, score(dimensions, AnalysisModule.FUND_FLOW_CAPITAL)));
        result.put(AnalysisModule.EVENT_CATALYST, events(snapshot, score(dimensions, AnalysisModule.EVENT_CATALYST)));
        result.put(AnalysisModule.FUNDAMENTAL_EXPECTATION, fundamentals(snapshot, score(dimensions, AnalysisModule.FUNDAMENTAL_EXPECTATION)));
        result.put(AnalysisModule.VALUATION_INDUSTRY, valuation(snapshot, score(dimensions, AnalysisModule.VALUATION_INDUSTRY)));
        return Map.copyOf(result);
    }

    private ModuleAnalysis technical(StockResearchSnapshot snapshot, EvidenceScore score) {
        Object payload = snapshot.technical().payload().orElse(null);
        if (!(payload instanceof TechnicalSnapshot technical)) {
            return unavailable(AnalysisModule.TECHNICAL_PRICE_VOLUME, "技术与量价数据不足",
                    "缺少可用的技术指标快照");
        }
        Map<String, IndicatorCard> cards = new LinkedHashMap<>();
        technical.cards().forEach(card -> cards.putIfAbsent(card.id(), card));
        List<ReportFact> facts = new ArrayList<>();
        Instant observedAt = observedAt(snapshot.technical());
        addCard(facts, cards, "SMA_20", "SMA20", observedAt);
        addCard(facts, cards, "SMA_60", "SMA60", observedAt);
        addCard(facts, cards, "MACD_12_26_9", "MACD", observedAt);
        addCard(facts, cards, "RSI_6", "RSI6", observedAt);
        addCard(facts, cards, "RETURN_20", "20日收益", observedAt);
        addCard(facts, cards, "VOLUME_RATIO_20", "20日量比", observedAt);
        addCard(facts, cards, "NATR_14", "NATR14", observedAt);
        addCard(facts, cards, "MAX_DRAWDOWN", "最大回撤", observedAt);

        IndicatorCard sma20 = cards.get("SMA_20");
        IndicatorCard sma60 = cards.get("SMA_60");
        Direction direction = direction(score);
        String relation = comparable(sma20, sma60)
                ? "SMA20为" + number(sma20.value()) + "，" + comparison(sma20.value(), sma60.value())
                        + "SMA60的" + number(sma60.value())
                : "SMA20与SMA60缺少可比值";
        String conclusion = relation + "；趋势、动量和量价信号综合为" + direction.label();
        List<AnalysisSignal> signals = new ArrayList<>();
        if (comparable(sma20, sma60)) {
            boolean stronger = sma20.value() > sma60.value();
            signals.add(new AnalysisSignal("technical-sma-relationship",
                    stronger ? Direction.STRONGER : Direction.WEAKER, 70,
                    stronger ? "SMA20高于SMA60" : "SMA20低于SMA60",
                    "中短期均线相对位置用于识别趋势方向，但需由动量和成交量确认",
                    List.of("technical-sma20", "technical-sma60"), "SMA20与SMA60发生反向交叉"));
        }
        List<String> counter = new ArrayList<>();
        IndicatorCard volume = cards.get("VOLUME_RATIO_20");
        if (volume != null && volume.value() != null && volume.value() < 1) {
            counter.add("20日量比低于1，成交量未确认当前趋势");
        }
        IndicatorCard drawdown = cards.get("MAX_DRAWDOWN");
        if (drawdown != null && drawdown.value() != null && drawdown.value() <= -20) {
            counter.add("历史最大回撤较大，趋势结论面临尾部风险约束");
        }
        return module(AnalysisModule.TECHNICAL_PRICE_VOLUME, direction, conclusion, facts, signals,
                List.of("趋势跟随用于识别均线方向，动量与量价指标用于交叉确认；单一指标不能独立形成结论"),
                counter, List.of("技术指标基于历史日线计算，不代表未来收益"), List.of("technical"));
    }

    private ModuleAnalysis flowAndCapital(StockResearchSnapshot snapshot, EvidenceScore score) {
        List<ReportFact> facts = new ArrayList<>();
        List<AnalysisSignal> signals = new ArrayList<>();
        List<String> counter = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        FundFlowSummary flowSummary = snapshot.fundFlowSummary().payload().orElse(null);
        boolean directional = false;
        if (flowSummary != null) {
            Instant observedAt = observedAt(snapshot.fundFlowSummary());
            addFlowWindowFacts(facts, 5, flowSummary.fiveDay(), observedAt);
            addFlowWindowFacts(facts, 20, flowSummary.twentyDay(), observedAt);
            BigDecimal sum5 = flowSummary.fiveDay() == null ? null : flowSummary.fiveDay().mainNetYuan();
            BigDecimal sum20 = flowSummary.twentyDay() == null ? null : flowSummary.twentyDay().mainNetYuan();
            if (!facts.isEmpty()) sources.add("fundFlowSummary");
            if (sum20 != null) {
                Direction flowDirection = sum20.signum() > 0 ? Direction.STRONGER
                        : sum20.signum() < 0 ? Direction.WEAKER : Direction.NEUTRAL;
                List<String> mainFactIds = sum5 == null
                        ? List.of("flow-main-20d")
                        : List.of("flow-main-5d", "flow-main-20d");
                signals.add(new AnalysisSignal("flow-main-direction", flowDirection, 75,
                        "近20日主力资金" + (sum20.signum() > 0 ? "净流入"
                                : sum20.signum() < 0 ? "净流出" : "净额为零"),
                        "多日累计资金流用于降低单日噪声，但只能说明已发生交易",
                        mainFactIds, "20日累计资金方向反转"));
                directional = true;
                if (sum5 != null && sum5.signum() != 0 && sum20.signum() != 0
                        && sum5.signum() != sum20.signum()) {
                    counter.add("近5日与近20日资金方向相反，短期和中期信号冲突");
                }
            }
        }
        Object capitalPayload = snapshot.capital().payload().orElse(null);
        if (capitalPayload instanceof CapitalData capital) {
            directional |= addCapitalSignals(facts, signals, counter, capital, observedAt(snapshot.capital()));
            sources.add("capital");
        }
        if (!directional) {
            return new ModuleAnalysis(AnalysisModule.FUND_FLOW_CAPITAL, Direction.INSUFFICIENT,
                    "资金与筹码缺少可判断的规模或变化数据", "LOW", facts, List.of(),
                    List.of("资金流与筹码变化必须结合金额、比例、方向和时间判断，记录条数本身不代表方向"),
                    counter, List.of("现有记录缺少规模或连续变化值，不能据此判断资金方向"), sources);
        }
        return module(AnalysisModule.FUND_FLOW_CAPITAL, direction(score),
                "主力资金与结构化筹码信号综合为" + direction(score).label(), facts, signals,
                List.of("短周期资金流反映已发生交易，中周期融资、股东和大宗数据用于验证筹码结构"),
                counter, List.of("资金行为不能单独证明未来价格方向"), sources);
    }

    private boolean addCapitalSignals(List<ReportFact> facts, List<AnalysisSignal> signals,
            List<String> counter, CapitalData capital, Instant observedAt) {
        boolean directional = false;
        List<CapitalData.MarginRecord> margin = capital.marginHistory().stream()
                .filter(item -> item.date() != null && item.financingBalanceYuan() != null)
                .sorted(Comparator.comparing(CapitalData.MarginRecord::date)).toList();
        if (margin.size() >= 2) {
            BigDecimal first = margin.getFirst().financingBalanceYuan();
            BigDecimal last = margin.getLast().financingBalanceYuan();
            BigDecimal change = last.subtract(first);
            facts.add(fact("capital-margin-change", "融资余额变化", change, "元", "capital", observedAt));
            signals.add(new AnalysisSignal("capital-margin-direction",
                    change.signum() > 0 ? Direction.STRONGER : change.signum() < 0 ? Direction.WEAKER : Direction.NEUTRAL,
                    45, "融资余额" + (change.signum() > 0 ? "增加" : change.signum() < 0 ? "减少" : "持平"),
                    "连续融资余额变化用于观察杠杆资金方向，需同时考虑价格和市场整体融资变化",
                    List.of("capital-margin-change"), "融资余额方向反转"));
            directional = true;
        }
        BigDecimal dragon = capital.dragonTigerRecords().stream().map(CapitalData.DragonTigerRecord::netBuyYuan)
                .filter(value -> value != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (dragon.signum() != 0) {
            facts.add(fact("capital-dragon-net", "龙虎榜净买入", dragon, "元", "capital", observedAt));
            directional = true;
        }
        BigDecimal premium = capital.blockTrades().stream().map(CapitalData.BlockTrade::premiumPercent)
                .filter(value -> value != null).findFirst().orElse(null);
        if (premium != null) {
            facts.add(fact("capital-block-premium", "大宗交易溢价率", premium, "%", "capital", observedAt));
            if (premium.signum() < 0) counter.add("大宗交易为折价成交，可能削弱筹码信号");
            directional = true;
        }
        return directional;
    }

    private ModuleAnalysis events(StockResearchSnapshot snapshot, EvidenceScore score) {
        List<Announcement> announcements = typedList(snapshot.announcements().payload().orElse(null), Announcement.class);
        List<ReportFact> facts = new ArrayList<>();
        List<AnalysisSignal> signals = new ArrayList<>();
        List<String> counter = new ArrayList<>();
        int index = 0;
        for (Announcement item : announcements.stream().limit(10).toList()) {
            String id = "event-announcement-" + (++index);
            facts.add(new ReportFact(id, "公告", safe(item.title()) + "（" + safe(item.type()) + "）",
                    "announcements", observedAt(snapshot.announcements())));
            String text = safe(item.title()) + safe(item.type());
            Direction direction = containsAny(text, "预增", "增持", "回购", "分红", "中标") ? Direction.STRONGER
                    : containsAny(text, "预减", "减持", "诉讼", "违规", "亏损") ? Direction.WEAKER : Direction.NEUTRAL;
            if (direction != Direction.NEUTRAL) {
                signals.add(new AnalysisSignal("event-signal-" + index, direction, 50,
                        safe(item.title()), "公告事件按已披露类型分类，影响方向仍需后续经营数据验证",
                        List.of(id), "公告被更正、取消或影响期结束"));
            }
        }
        if (signals.stream().map(AnalysisSignal::direction).distinct().count() > 1) {
            counter.add("当前窗口同时存在正向和负向事件，不能合并成单一催化判断");
        }
        if (facts.isEmpty()) {
            return unavailable(AnalysisModule.EVENT_CATALYST, "当前窗口没有足够的结构化事件证据",
                    "缺少可分类的公告和资本事件");
        }
        return module(AnalysisModule.EVENT_CATALYST, direction(score),
                signals.isEmpty() ? "公告已披露，但没有形成明确方向事件" : "公告事件综合为" + direction(score).label(),
                facts, signals, List.of("事件研究区分已披露事实、计划和市场解释，并按事件有效期持续复核"),
                counter, List.of("事件分类不等同于对最终财务影响的确认"), List.of("announcements"));
    }

    private ModuleAnalysis fundamentals(StockResearchSnapshot snapshot, EvidenceScore score) {
        List<ReportFact> facts = new ArrayList<>();
        List<AnalysisSignal> signals = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        Object payload = snapshot.fundamentals().payload().orElse(null);
        if (payload instanceof FundamentalData data) {
            data.yearOverYearPercent().entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(8)
                    .forEach(entry -> facts.add(fact("fundamental-yoy-" + Integer.toUnsignedString(entry.getKey().hashCode(), 36),
                            entry.getKey() + "同比", entry.getValue(), "%", "fundamentals", observedAt(snapshot.fundamentals()))));
            data.yearOverYearPercent().entrySet().stream()
                    .filter(entry -> containsAny(entry.getKey(), "收入", "营收", "利润", "净利", "现金流"))
                    .filter(entry -> entry.getValue() != null).forEach(entry -> signals.add(new AnalysisSignal(
                            "fundamental-signal-" + Integer.toUnsignedString(entry.getKey().hashCode(), 36),
                            entry.getValue().signum() > 0 ? Direction.STRONGER
                                    : entry.getValue().signum() < 0 ? Direction.WEAKER : Direction.NEUTRAL,
                            55, entry.getKey() + "同比" + number(entry.getValue()) + "%",
                            "收入、利润和现金流的同比变化用于判断增长质量与持续性",
                            facts.stream().filter(fact -> fact.label().startsWith(entry.getKey())).map(ReportFact::id).toList(),
                            entry.getKey() + "同比方向反转")));
            sources.add("fundamentals");
        }
        List<ResearchItem> reports = typedList(snapshot.research().payload().orElse(null), ResearchItem.class);
        if (!reports.isEmpty()) {
            ConsensusForecast forecast = new ConsensusForecastCalculator().calculate(reports);
            facts.add(new ReportFact("research-coverage", "机构覆盖数", forecast.coverage() + "家",
                    "research", observedAt(snapshot.research())));
            if (forecast.currentYearEpsMedian() != null) facts.add(fact("research-current-eps", "当年EPS中位数",
                    forecast.currentYearEpsMedian(), "", "research", observedAt(snapshot.research())));
            if (forecast.nextYearEpsMedian() != null) facts.add(fact("research-next-eps", "次年EPS中位数",
                    forecast.nextYearEpsMedian(), "", "research", observedAt(snapshot.research())));
            if (forecast.coverage() < 2) limitations.add("机构覆盖不足两家，一致预期不形成方向信号");
            sources.add("research");
        }
        if (facts.isEmpty()) return unavailable(AnalysisModule.FUNDAMENTAL_EXPECTATION,
                "基本面与机构预期证据不足", "缺少财务同比和机构预测数据");
        return module(AnalysisModule.FUNDAMENTAL_EXPECTATION, direction(score),
                "经营数据与机构预期综合为" + direction(score).label(), facts, signals,
                List.of("增长质量需由收入、利润与经营现金流共同验证；一致预期关注覆盖度和预测分歧"),
                List.of(), limitations, sources);
    }

    private ModuleAnalysis valuation(StockResearchSnapshot snapshot, EvidenceScore score) {
        Object payload = snapshot.industryValuation().payload().orElse(null);
        var quote = snapshot.quote().payload().orElse(null);
        if (!(payload instanceof IndustryValuationData data) || quote == null) {
            return unavailable(AnalysisModule.VALUATION_INDUSTRY, "行业估值证据不足",
                    "缺少个股估值或行业可比样本");
        }
        List<ReportFact> facts = new ArrayList<>();
        Instant observedAt = observedAt(snapshot.industryValuation());
        if (quote.peTtm() != null) facts.add(fact("valuation-target-pe", "个股PE(TTM)", quote.peTtm(), "", "quote", observedAt(snapshot.quote())));
        if (data.peMedian() != null) facts.add(fact("valuation-industry-pe", "行业PE中位数", data.peMedian(), "", "valuation", observedAt));
        BigDecimal pePremium = premium(quote.peTtm(), data.peMedian());
        if (pePremium != null) facts.add(fact("valuation-pe-premium", "PE相对溢价", pePremium, "%", "valuation", observedAt));
        if (quote.pb() != null) facts.add(fact("valuation-target-pb", "个股PB", quote.pb(), "", "quote", observedAt(snapshot.quote())));
        if (data.pbMedian() != null) facts.add(fact("valuation-industry-pb", "行业PB中位数", data.pbMedian(), "", "valuation", observedAt));
        facts.add(new ReportFact("valuation-samples", "有效PE样本", data.validPeSamples() + "家", "valuation", observedAt));
        List<AnalysisSignal> signals = pePremium == null ? List.of() : List.of(new AnalysisSignal(
                "valuation-pe-relative", pePremium.signum() < 0 ? Direction.STRONGER
                        : pePremium.signum() > 0 ? Direction.WEAKER : Direction.NEUTRAL, 60,
                "个股PE较行业中位数" + (pePremium.signum() < 0 ? "折价" : pePremium.signum() > 0 ? "溢价" : "持平")
                        + pePremium.abs().stripTrailingZeros().toPlainString() + "%",
                "相对估值比较同一行业样本，但不能脱离盈利增长和商业模式解释",
                List.of("valuation-target-pe", "valuation-industry-pe", "valuation-pe-premium"),
                "盈利预期或行业可比样本发生显著变化"));
        return module(AnalysisModule.VALUATION_INDUSTRY, direction(score),
                pePremium == null ? "PE缺少正值可比基准，估值结论降级" : signals.getFirst().conclusion(),
                facts, signals, List.of("相对估值法比较个股与行业中位数，并要求正值、同口径和足够样本"),
                List.of("低估值不能单独构成正向判断，高估值也不等同于确定性负向结论"),
                List.of("行业样本的业务结构和盈利周期可能不可比，需结合基本面验证"),
                List.of("quote", "valuation"));
    }

    private static ModuleAnalysis module(AnalysisModule module, Direction direction, String conclusion,
            List<ReportFact> facts, List<AnalysisSignal> signals, List<String> methodology,
            List<String> counter, List<String> limitations, List<String> sources) {
        String confidence = facts.size() >= 5 && !signals.isEmpty() ? "HIGH" : facts.size() >= 2 ? "MEDIUM" : "LOW";
        return new ModuleAnalysis(module, direction, conclusion, confidence, facts, signals,
                methodology, counter, limitations, sources.stream().distinct().toList());
    }

    private static ModuleAnalysis unavailable(AnalysisModule module, String conclusion, String limitation) {
        return new ModuleAnalysis(module, Direction.INSUFFICIENT, conclusion, "LOW", List.of(), List.of(),
                methodology(module), List.of(), List.of(limitation), List.of());
    }

    private static List<String> methodology(AnalysisModule module) {
        return switch (module) {
            case TECHNICAL_PRICE_VOLUME -> List.of("趋势、动量和量价需要交叉确认");
            case FUND_FLOW_CAPITAL -> List.of("资金与筹码必须结合金额、比例和时间变化");
            case EVENT_CATALYST -> List.of("事件按已披露事实、计划和影响期分类");
            case FUNDAMENTAL_EXPECTATION -> List.of("增长质量由收入、利润和现金流共同验证");
            case VALUATION_INDUSTRY -> List.of("相对估值要求同口径且可比的行业样本");
        };
    }

    private static EvidenceScore score(Map<String, EvidenceScore> scores, AnalysisModule module) {
        return scores == null ? null : scores.get(module.name());
    }

    private static Direction direction(EvidenceScore score) {
        if (score == null || !score.usable()) return Direction.INSUFFICIENT;
        return score.rawScore() > 20 ? Direction.STRONGER : score.rawScore() < -20 ? Direction.WEAKER : Direction.NEUTRAL;
    }

    private static void addCard(List<ReportFact> facts, Map<String, IndicatorCard> cards,
            String id, String label, Instant observedAt) {
        IndicatorCard card = cards.get(id);
        if (card != null && card.value() != null) {
            facts.add(new ReportFact("technical-" + id.toLowerCase().replace("_", ""), label,
                    number(card.value()) + safe(card.unit()), "technical", observedAt));
        }
    }

    private static ReportFact fact(String id, String label, BigDecimal value,
            String unit, String source, Instant observedAt) {
        return new ReportFact(id, label, number(value) + safe(unit), source, observedAt);
    }

    private static void addFlowWindowFacts(
            List<ReportFact> facts, int days, FundFlowWindowSummary window, Instant observedAt) {
        if (window == null) return;
        addFlowFact(facts, "flow-main-" + days + "d", days, "主力", window.mainNetYuan(), observedAt);
        addFlowFact(facts, "flow-super-large-" + days + "d", days, "超大单", window.superLargeNetYuan(), observedAt);
        addFlowFact(facts, "flow-large-" + days + "d", days, "大单", window.largeNetYuan(), observedAt);
        addFlowFact(facts, "flow-medium-" + days + "d", days, "中单", window.mediumNetYuan(), observedAt);
        addFlowFact(facts, "flow-small-" + days + "d", days, "小单", window.smallNetYuan(), observedAt);
    }

    private static void addFlowFact(
            List<ReportFact> facts, String id, int days, String category,
            BigDecimal value, Instant observedAt) {
        if (value != null) {
            facts.add(fact(id, "近" + days + "日" + category + "净流入", value,
                    "元", "fundFlowSummary", observedAt));
        }
    }

    private static BigDecimal premium(BigDecimal target, BigDecimal median) {
        if (target == null || median == null || target.signum() <= 0 || median.signum() <= 0) return null;
        return target.divide(median, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static boolean comparable(IndicatorCard left, IndicatorCard right) {
        return left != null && right != null && left.value() != null && right.value() != null;
    }

    private static String comparison(double left, double right) {
        return left > right ? "高于" : left < right ? "低于" : "等于";
    }

    private static Instant observedAt(DataSection<?> section) {
        if (section == null || section.provenance().isEmpty()) return null;
        var provenance = section.provenance().orElseThrow();
        return provenance.providerTimestamp() == null ? provenance.fetchedAt() : provenance.providerTimestamp();
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String number(Double value) {
        if (value == null) return "";
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private static <T> List<T> typedList(Object payload, Class<T> type) {
        if (!(payload instanceof List<?> values)) return List.of();
        return values.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
