package com.astock.agent.analysis.candlestick;

import com.astock.agent.analysis.candlestick.CandlestickAnalysis.BookReference;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.CompletionStatus;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.ConfirmationStatus;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.ConfluenceAssessment;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.ConfluenceFactor;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.Direction;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.EvidenceKind;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.LevelRole;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.LevelStatus;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.Methodology;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.PatternSignal;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.PriceLevel;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.RiskAssessment;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.Trend;
import com.astock.agent.analysis.candlestick.CandlestickAnalysis.TrendContext;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.technical.BarSeriesFactory;
import com.astock.agent.technical.Timeframe;
import com.astock.agent.knowledge.BookKnowledgeService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 依据尼森框架对已校验 OHLCV 序列做确定性蜡烛图分析。 */
public final class CandlestickAnalysisService {

    private static final List<String> ANALYSIS_SEQUENCE = List.of(
            "前置趋势", "形态构成", "相对位置", "后续确认", "支撑阻挡与失效", "风险报偿", "其他技术信号");
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final LocalTime DAILY_CLOSE_SAFETY_TIME = LocalTime.of(15, 5);

    private final BarSeriesFactory seriesFactory;
    private final Clock clock;
    private final BookKnowledgeService knowledge;

    public CandlestickAnalysisService(BarSeriesFactory seriesFactory, Clock clock) {
        this(seriesFactory, clock, new BookKnowledgeService());
    }

    public CandlestickAnalysisService(BarSeriesFactory seriesFactory, Clock clock,
            BookKnowledgeService knowledge) {
        this.seriesFactory = Objects.requireNonNull(seriesFactory, "seriesFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    }

    public CandlestickAnalysis analyze(List<DailyBar> dailyBars, Timeframe timeframe) {
        Objects.requireNonNull(dailyBars, "dailyBars");
        Objects.requireNonNull(timeframe, "timeframe");
        List<DailyBar> bars = new ArrayList<>(seriesFactory.aggregate(dailyBars, timeframe));
        bars.sort(Comparator.comparing(DailyBar::date));
        LocalDate excludedDate = null;
        if (!bars.isEmpty() && isCurrentPeriodIncomplete(bars.getLast().date(), timeframe)) {
            excludedDate = bars.removeLast().date();
        }
        if (bars.size() < 20) {
            throw new IllegalArgumentException("At least 20 completed bars are required for candlestick analysis");
        }

        CompletionStatus completion = excludedDate == null
                ? new CompletionStatus(true, null, "分析序列中的最后一根 K 线已完成")
                : new CompletionStatus(false, excludedDate, "当前周期尚未完成，已排除该 K 线，不据此确认形态");
        TrendContext trend = trendContext(bars);
        List<PatternSignal> signals = detectPatterns(bars);
        PatternSignal primary = signals.stream()
                .filter(signal -> signal.confirmationStatus() != ConfirmationStatus.INVALIDATED)
                .findFirst().orElse(null);
        List<PriceLevel> levels = priceLevels(bars, primary);
        ConfluenceAssessment confluence = confluence(bars, trend, primary, levels);
        RiskAssessment risk = risk(bars, primary, levels);

        return new CandlestickAnalysis(
                timeframe, bars.getLast().date(), bars.size(), completion, trend, signals,
                confluence, risk, levels, methodology(signals),
                List.of("反转形态表示原趋势可能变化的警告，不保证立即形成反向趋势",
                        "形态阈值是可审计的工程化近似，不等同于书中给出的机械交易系统",
                        "本分析仅用于研究，不构成个性化投资建议"), previousSession(bars, timeframe));
    }

    private CandlestickAnalysis.SessionReview previousSession(List<DailyBar> completedBars, Timeframe timeframe) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(CHINA);
        LocalDate today = now.toLocalDate();
        boolean includeToday = !now.toLocalTime().isBefore(DAILY_CLOSE_SAFETY_TIME);
        // 完成周期已由 analyze 依据 timeframe 剔除；这里只排除未来日期，保证复盘不使用之后行情。
        List<DailyBar> history = completedBars.stream().filter(bar -> bar.date().isBefore(today)
                        || (includeToday && bar.date().equals(today)))
                .sorted(Comparator.comparing(DailyBar::date)).toList();
        if (history.isEmpty()) return null;
        String period = periodLabel(timeframe);
        String unit = switch (timeframe) {
            case DAILY -> "日";
            case WEEKLY -> "周";
            case MONTHLY -> "月";
        };
        String within = switch (timeframe) {
            case DAILY -> "日内";
            case WEEKLY -> "周内";
            case MONTHLY -> "月内";
        };
        String thisPeriod = switch (timeframe) {
            case DAILY -> "当日";
            case WEEKLY -> "当周";
            case MONTHLY -> "当月";
        };
        String thatPeriod = switch (timeframe) {
            case DAILY -> "该日";
            case WEEKLY -> "该周";
            case MONTHLY -> "该月";
        };
        String averageVolume = timeframe == Timeframe.DAILY ? "20 日均量" : "20 期均量";
        String span = switch (timeframe) {
            case DAILY -> "跨日";
            case WEEKLY -> "跨周";
            case MONTHLY -> "跨月";
        };
        DailyBar bar = history.getLast();
        BigDecimal range = bar.high().subtract(bar.low());
        BigDecimal realBody = body(bar);
        BigDecimal upper = bar.high().subtract(bodyHigh(bar));
        BigDecimal lower = bodyLow(bar).subtract(bar.low());
        BigDecimal bodyRatio = sharePercent(realBody, range);
        BigDecimal upperRatio = sharePercent(upper, range);
        BigDecimal lowerRatio = sharePercent(lower, range);
        String type = bullish(bar) ? "阳线" : bearish(bar) ? "阴线" : "开收持平";
        String shape;
        String interpretation;
        if (range.signum() == 0) {
            shape = "无振幅线";
            interpretation = "开高低收相同，无法从实体与影线比例判断力量变化；仅凭无振幅不能判断停牌或涨跌停。";
        } else if (bodyRatio.doubleValue() <= 5) {
            shape = "十字线轮廓";
            interpretation = "开收接近，" + within + "波动未转为明显实体，" + thatPeriod + "方向推进不足；十字轮廓本身不构成反转确认。";
        } else if (upperRatio.doubleValue() >= 60) {
            shape = "长上影线";
            interpretation = "收盘未能保持" + within + "高位，提示上方压力。";
        } else if (lowerRatio.doubleValue() >= 60) {
            shape = "长下影线";
            interpretation = "收盘脱离" + within + "低点，提示下探后有所承接。";
        } else if (bodyRatio.doubleValue() >= 70) {
            shape = "实体主导";
            interpretation = bullish(bar) ? "实体占" + thisPeriod + "振幅较大，收盘明显高于开盘，" + thatPeriod + "买方较占优势。"
                    : "实体占" + thisPeriod + "振幅较大，收盘明显低于开盘，" + thatPeriod + "卖方较占优势。";
        } else if (bodyRatio.doubleValue() <= 30) {
            shape = "小实体线";
            interpretation = "开收差相对" + thisPeriod + "振幅较小，" + thatPeriod + "方向推进有限；单" + unit + "小实体不足以判定相对历史的动能收缩。";
        } else {
            shape = "普通实体线";
            interpretation = "实体与影线共同构成" + thisPeriod + "波动，单根轮廓没有突出特征。";
        }
        List<DailyBar> prior = history.subList(Math.max(0, history.size() - 21), history.size() - 1);
        BigDecimal change = prior.isEmpty() || prior.getLast().close().signum() == 0 ? null
                : percent(bar.close(), prior.getLast().close());
        BigDecimal volumeRatio = null;
        if (prior.size() == 20) {
            BigDecimal total = prior.stream().map(DailyBar::volumeShares).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.signum() > 0) volumeRatio = bar.volumeShares().multiply(BigDecimal.valueOf(20))
                    .divide(total, 2, RoundingMode.HALF_UP);
        }
        String trend = switch (trendBefore(history, history.size() - 1, 5)) {
            case UP -> "此前 5 根" + period + "收盘趋势向上";
            case DOWN -> "此前 5 根" + period + "收盘趋势向下";
            case SIDEWAYS -> "此前 5 根" + period + "收盘趋势横向";
            case INSUFFICIENT -> "此前" + period + "不足 5 根，前置趋势不可判定";
        };
        String location = "此前价格区间样本不足";
        if (!prior.isEmpty()) {
            BigDecimal high = prior.stream().map(DailyBar::high).max(BigDecimal::compareTo).orElseThrow();
            BigDecimal low = prior.stream().map(DailyBar::low).min(BigDecimal::compareTo).orElseThrow();
            location = "此前 " + prior.size() + " 根" + period + "区间 " + money(low) + "—" + money(high) + " 元；"
                    + (bar.close().compareTo(high) > 0 ? "收盘位于区间上方"
                    : bar.close().compareTo(low) < 0 ? "收盘位于区间下方" : "收盘仍在区间内");
        }
        List<PatternSignal> matches = detectPatterns(history).stream()
                .filter(signal -> signal.endDate().equals(bar.date())).toList();
        if ("长上影线".equals(shape)) {
            interpretation = upperShadowInterpretation(bar, prior, trend, volumeRatio, period, within,
                    thisPeriod, unit, averageVolume);
        } else if ("长下影线".equals(shape)) {
            interpretation = lowerShadowInterpretation(bar, prior, trend, volumeRatio, period, within,
                    thisPeriod, span, averageVolume);
        } else {
            interpretation += sessionContext(bar, prior, trend, location, volumeRatio, period, thisPeriod,
                    unit, averageVolume);
        }
        return new CandlestickAnalysis.SessionReview(bar, type, shape, realBody, upper, lower,
                bodyRatio, upperRatio, lowerRatio, change, volumeRatio, interpretation, trend, location,
                "截至 " + bar.date() + " 尚无后续完整" + period + "，" + span + "确认状态为未确认。价格边界判据：后续完整"
                        + period + "收盘严格高于 " + money(bar.high()) + " 元记为向上突破；收盘严格低于 "
                        + money(bar.low()) + " 元记为向下破位；收盘处于两者之间或等于边界，记为未突破。该判据不等同于趋势反转。",
                previousSessionDateNote(timeframe, bar, includeToday), matches,
                knowledge.forSessionShape(shape));
    }

    private static String periodLabel(Timeframe timeframe) {
        return switch (timeframe) {
            case DAILY -> "日线";
            case WEEKLY -> "周线";
            case MONTHLY -> "月线";
        };
    }

    private static String previousSessionDateNote(Timeframe timeframe, DailyBar bar, boolean includeToday) {
        String freshness = "复盘截至 " + bar.date() + "，不含之后行情。休市、停牌或数据滞后均可能使日期提前，请核对来源时效。";
        return switch (timeframe) {
            case DAILY -> "按上海时间 15:05 判断日线完成；"
                    + (includeToday ? "已过安全时间，可纳入当天收盘日线" : "尚未到安全时间，排除当天日线")
                    + "。" + freshness;
            case WEEKLY -> "周线由日 K 按自然周聚合：首日开盘、末日收盘、最高最低取极值、成交量求和；"
                    + "周五 15:05 前当前周未完成，排除该周 K 线。" + freshness;
            case MONTHLY -> "月线由日 K 按自然月聚合：首日开盘、末日收盘、最高最低取极值、成交量求和；"
                    + "当前月未结束前排除该月 K 线。" + freshness;
        };
    }

    /** 用复盘周期之前的区间定义前高，避免把当期高点混入比较基准。 */
    private static String upperShadowInterpretation(DailyBar bar, List<DailyBar> prior,
            String trend, BigDecimal volumeRatio, String period, String within, String thisPeriod,
            String unit, String averageVolume) {
        if (prior.isEmpty()) return "收盘未能保持" + within + "高位；此前" + period + "缺失，无法判定区间位置与压力状态。";
        BigDecimal high = prior.stream().map(DailyBar::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal low = prior.stream().map(DailyBar::low).min(BigDecimal::compareTo).orElseThrow();
        String assessment;
        if (bar.close().compareTo(high) > 0) {
            assessment = "收盘已突破前高，已有越过原区间上沿的证据，但单" + unit + "突破不等于持续站稳";
        } else if (bar.close().compareTo(low) < 0) {
            assessment = "收盘跌破此前区间低点 " + money(low) + " 元，区间支撑失守，当前结构偏弱，上方压力尚未消化";
        } else if (bar.high().compareTo(high) >= 0) {
            assessment = "冲高未站稳前高，按收盘突破判据，前高压力尚未消化";
        } else {
            assessment = within + "最高价尚未触及前高，收盘仍在此前区间内；本次上影反映区间内上冲回落，不能归因为前高受阻";
        }
        String volume = volumeRatio == null ? "此前 " + averageVolume + "不可用，量能证据不足"
                : "成交量为此前 " + averageVolume + "的 " + volumeRatio + " 倍，"
                        + (volumeRatio.compareTo(new BigDecimal("1.20")) >= 0
                        ? "达到放量阈值（1.20 倍），但放量本身不代表压力已消化"
                        : "未达到放量阈值（1.20 倍）");
        return "收盘未能保持" + within + "高位；" + trend + "。此前 " + prior.size() + " 根" + period + "前高 "
                + money(high) + " 元，" + thisPeriod + "最高 " + money(bar.high()) + " 元、收盘 " + money(bar.close())
                + " 元：" + assessment + "。"
                + (prior.size() < 20 ? "此前仅有 " + prior.size() + " 根" + period + "，不足 20 根，区间判断样本有限。" : "")
                + volume + "。" + thisPeriod + "上影压力仍未获后续收盘突破确认，"
                + "截至 " + bar.date() + " 尚无后续完整" + period + "，不能判定未来能否消化。";
    }

    private static String lowerShadowInterpretation(DailyBar bar, List<DailyBar> prior,
            String trend, BigDecimal volumeRatio, String period, String within, String thisPeriod,
            String span, String averageVolume) {
        if (prior.isEmpty()) return "收盘脱离" + within + "低点，但此前" + period + "缺失，支撑位置不可判定。";
        BigDecimal low = prior.stream().map(DailyBar::low).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal high = prior.stream().map(DailyBar::high).max(BigDecimal::compareTo).orElseThrow();
        String assessment;
        if (bar.close().compareTo(low) < 0) {
            assessment = "收盘仍低于前低，原区间支撑已失守；下影仅表示盘中回收，未修复破位";
        } else if (bar.close().compareTo(low) == 0) {
            assessment = "收盘仅回到前低，尚未收回其上方，支撑修复证据不足";
        } else if (bar.close().compareTo(high) > 0) {
            assessment = "收盘已突破前高，" + within + "回收同时形成区间向上突破，尚不等于持续站稳";
        } else if (bar.low().compareTo(low) <= 0) {
            assessment = "下探前低后收回，前低获得" + thisPeriod + "收盘层面的承接证据，尚不构成" + span
                    + "守稳或反转确认";
        } else {
            assessment = "最低价未触及前低，属于区间内回落后的承接，不能据此认定前低支撑已获验证";
        }
        return trend + "。此前 " + prior.size() + " 根" + period + "区间 " + money(low) + "—" + money(high)
                + " 元；" + thisPeriod + "最低 " + money(bar.low()) + " 元，收盘 " + money(bar.close()) + " 元："
                + assessment + "。" + sessionVolumeEvidence(volumeRatio, averageVolume)
                + (prior.size() < 20 ? "此前仅有 " + prior.size() + " 根" + period + "，不足 20 根，区间判断样本有限。" : "");
    }

    private static String sessionContext(DailyBar bar, List<DailyBar> prior, String trend,
            String location, BigDecimal volumeRatio, String period, String thisPeriod, String unit,
            String averageVolume) {
        String conclusion = "此前" + period + "缺失，无法判定区间突破";
        if (!prior.isEmpty()) {
            BigDecimal high = prior.stream().map(DailyBar::high).max(BigDecimal::compareTo).orElseThrow();
            BigDecimal low = prior.stream().map(DailyBar::low).min(BigDecimal::compareTo).orElseThrow();
            conclusion = bar.close().compareTo(high) > 0 ? "收盘已突破此前区间上沿，形成" + thisPeriod + "向上突破"
                    : bar.close().compareTo(low) < 0 ? "收盘跌破此前区间下沿，原区间支撑失守"
                    : "收盘仍在此前区间内，未形成区间突破";
        }
        return trend + "；" + location + "。当前结论：收盘 " + money(bar.close()) + " 元，"
                + conclusion + "；单" + unit + "区间状态不等同于趋势反转。" + sessionVolumeEvidence(volumeRatio, averageVolume)
                + (prior.size() < 20 ? "此前仅有 " + prior.size() + " 根" + period + "，不足 20 根，区间判断样本有限。" : "");
    }

    private static String sessionVolumeEvidence(BigDecimal volumeRatio, String averageVolume) {
        if (volumeRatio == null) return "此前 " + averageVolume + "不可用，量能证据不足。";
        return "成交量为此前 " + averageVolume + "的 " + volumeRatio + " 倍，"
                + (volumeRatio.compareTo(new BigDecimal("1.20")) >= 0
                ? "达到放量阈值（1.20 倍），成交活跃但不能单凭成交量确认支撑或反转。"
                : "未达到放量阈值（1.20 倍），缺少放量配合。");
    }

    private static BigDecimal sharePercent(BigDecimal value, BigDecimal range) {
        return range.signum() == 0 ? null : value.multiply(BigDecimal.valueOf(100))
                .divide(range, 2, RoundingMode.HALF_UP);
    }

    private boolean isCurrentPeriodIncomplete(LocalDate barDate, Timeframe timeframe) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(CHINA);
        LocalDate today = now.toLocalDate();
        return switch (timeframe) {
            case DAILY -> barDate.equals(today) && now.toLocalTime().isBefore(DAILY_CLOSE_SAFETY_TIME);
            case WEEKLY -> sameTradingWeek(barDate, today)
                    && !(today.getDayOfWeek() == DayOfWeek.FRIDAY
                    && !now.toLocalTime().isBefore(DAILY_CLOSE_SAFETY_TIME));
            case MONTHLY -> YearMonth.from(barDate).equals(YearMonth.from(today));
        };
    }

    private static boolean sameTradingWeek(LocalDate left, LocalDate right) {
        WeekFields fields = WeekFields.ISO;
        return left.get(fields.weekBasedYear()) == right.get(fields.weekBasedYear())
                && left.get(fields.weekOfWeekBasedYear()) == right.get(fields.weekOfWeekBasedYear());
    }

    private static List<PatternSignal> detectPatterns(List<DailyBar> bars) {
        List<PatternSignal> result = new ArrayList<>();
        result.addAll(detectBullishEngulfing(bars));
        result.addAll(detectUmbrellaLines(bars));
        result.addAll(detectUpperShadowReversals(bars));
        result.addAll(detectDojiWarnings(bars));
        result.addAll(detectStarPatterns(bars));
        result.addAll(detectDarkCloudCover(bars));
        result.addAll(detectMirroredTwoLineReversals(bars));
        result.addAll(detectWindows(bars));
        result.addAll(detectHarami(bars));
        result.replaceAll(signal -> invalidateBrokenSignal(signal, bars));
        result.sort(Comparator.comparingInt(CandlestickAnalysisService::signalPriority)
                .thenComparing(PatternSignal::endDate, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingInt(PatternSignal::evidenceScore).reversed()));
        return List.copyOf(result);
    }

    private static int signalPriority(PatternSignal signal) {
        return signal.confirmationStatus() == ConfirmationStatus.INVALIDATED ? 1 : 0;
    }

    private static PatternSignal invalidateBrokenSignal(PatternSignal signal, List<DailyBar> bars) {
        return bars.stream().filter(bar -> bar.date().isAfter(signal.endDate()))
                .filter(bar -> breached(bar.close(), signal.invalidationPrice(), signal.direction()))
                .findFirst().map(bar -> new PatternSignal(signal.id(), signal.name(), signal.englishName(),
                        signal.family(), signal.direction(), signal.startDate(), signal.endDate(),
                        0, "INVALIDATED", ConfirmationStatus.INVALIDATED, signal.idealGeometry(),
                        signal.constructionEvidence(), signal.trendEvidence(), signal.locationEvidence(),
                        bar.date() + " 收市价突破失效位；后续回到原区间不会自动恢复该历史信号",
                        signal.invalidationPrice(), signal.invalidationRule(), signal.sourceChapter()))
                .orElse(signal);
    }

    private static boolean breached(BigDecimal close, BigDecimal boundary, Direction direction) {
        return direction == Direction.BULLISH ? close.compareTo(boundary) < 0
                : direction == Direction.BEARISH && close.compareTo(boundary) > 0;
    }

    /** 第六章只要求实体包裹；颜色相同或影线越界不排除孕线。比例为公开的工程阈值。 */
    private static List<PatternSignal> detectHarami(List<DailyBar> bars) {
        List<PatternSignal> result = new ArrayList<>();
        for (int index = Math.max(6, bars.size() - 15); index < bars.size(); index++) {
            DailyBar first = bars.get(index - 1);
            DailyBar second = bars.get(index);
            Trend prior = trendBefore(bars, index - 1, 5);
            boolean bottom = prior == Trend.DOWN && bearish(first);
            boolean top = prior == Trend.UP && bullish(first);
            if ((!bottom && !top) || !longBody(first)
                    || body(second).compareTo(body(first).multiply(BigDecimal.valueOf(0.35))) > 0
                    || bodyLow(second).compareTo(bodyLow(first)) <= 0
                    || bodyHigh(second).compareTo(bodyHigh(first)) >= 0
                    || second.high().compareTo(second.low()) <= 0) continue;
            boolean cross = body(second).compareTo(second.high().subtract(second.low())
                    .multiply(BigDecimal.valueOf(0.05))) <= 0;
            BigDecimal low = first.low().min(second.low());
            BigDecimal high = first.high().max(second.high());
            ConfirmationStatus status = ConfirmationStatus.AWAITING_CONFIRMATION;
            String confirmation = "等待后续完整 K 线收市价突破两根线的方向性极值；孕线也可能仅转为横盘";
            for (int next = index + 1; next < bars.size(); next++) {
                BigDecimal close = bars.get(next).close();
                if (bottom ? close.compareTo(low) < 0 : close.compareTo(high) > 0) {
                    status = ConfirmationStatus.INVALIDATED;
                    confirmation = bars.get(next).date() + " 收市价突破失效位，孕线警告失效";
                    break;
                }
                if (bottom ? close.compareTo(high) > 0 : close.compareTo(low) < 0) {
                    status = ConfirmationStatus.CONFIRMED;
                    confirmation = bars.get(next).date() + " 收市价突破形态" + (bottom ? "最高点" : "最低点") + "，得到方向确认";
                }
            }
            String suffix = cross ? "HARAMI_CROSS" : "HARAMI";
            result.add(new PatternSignal((bottom ? "BULLISH_" : "BEARISH_") + suffix,
                    (bottom ? "看涨" : "看跌") + (cross ? "十字孕线" : "孕线"),
                    cross ? "Harami cross" : "Harami", "双线反转", bottom ? Direction.BULLISH : Direction.BEARISH,
                    first.date(), second.date(), status == ConfirmationStatus.CONFIRMED ? 75 : 60,
                    status == ConfirmationStatus.CONFIRMED ? "MODERATE" : "WATCH", status, true,
                    List.of("第一根为顺原趋势的长实体", "第二根实体严格位于第一根实体内部，且不超过其 35%",
                            "不要求实体异色，影线可以越界"),
                    bottom ? "此前 5 期趋势下降" : "此前 5 期趋势上升", "原趋势末端的动能收缩警告",
                    confirmation, money(bottom ? low : high),
                    bottom ? "收市价跌破两根线最低点则失效" : "收市价升破两根线最高点则失效", "第六章 其他反转形态"));
        }
        return result;
    }

    private static List<PatternSignal> detectDojiWarnings(List<DailyBar> bars) {
        List<PatternSignal> result = new ArrayList<>();
        int from = Math.max(5, bars.size() - 15);
        for (int index = bars.size() - 1; index >= from; index--) {
            DailyBar bar = bars.get(index);
            BigDecimal range = bar.high().subtract(bar.low());
            if (range.signum() <= 0 || body(bar).divide(range, 8, RoundingMode.HALF_UP)
                    .compareTo(BigDecimal.valueOf(0.05)) > 0) continue;
            Trend prior = trendBefore(bars, index, 5);
            boolean top = prior == Trend.UP;
            boolean bottom = prior == Trend.DOWN;
            if (!top && !bottom) continue;
            Direction direction = top ? Direction.BEARISH : Direction.BULLISH;
            ConfirmationStatus status = ConfirmationStatus.AWAITING_CONFIRMATION;
            String confirmation = "十字线只表示原趋势可能进入转变过程，不会自动把趋势反转；等待下一期收市价确认";
            if (index < bars.size() - 1) {
                boolean confirmed = top ? bars.get(index + 1).close().compareTo(bar.close()) < 0
                        : bars.get(index + 1).close().compareTo(bar.close()) > 0;
                status = confirmed ? ConfirmationStatus.CONFIRMED : ConfirmationStatus.INVALIDATED;
                confirmation = confirmed ? "下一期收市价向警告方向运行，十字线得到确认"
                        : "下一期收市价未确认该警告，不据此改变趋势方向";
            }
            result.add(new PatternSignal(
                    top ? "DOJI_TOP" : "DOJI_BOTTOM", top ? "高位十字线" : "低位十字线",
                    "Doji", "十字线", direction, bar.date(), bar.date(),
                    status == ConfirmationStatus.CONFIRMED ? 78 : 62,
                    status == ConfirmationStatus.CONFIRMED ? "MODERATE" : "WATCH", status, true,
                    List.of("开市价与收市价之差不超过全幅价格区间的 5%", "实体近似为一条水平线"),
                    top ? "十字线出现于短期上涨趋势之后" : "十字线出现于短期下降趋势之后",
                    top ? "上涨段高位的犹疑警告" : "下降段低位的犹疑警告", confirmation,
                    money(top ? bar.high() : bar.low()),
                    top ? "收市价有效升破十字线最高点，则看跌警告失效"
                            : "收市价有效跌破十字线最低点，则看涨警告失效",
                    "第八章 神奇的十字线"));
        }
        return result;
    }

    private static List<PatternSignal> detectUpperShadowReversals(List<DailyBar> bars) {
        List<PatternSignal> result = new ArrayList<>();
        int from = Math.max(5, bars.size() - 15);
        for (int index = bars.size() - 1; index >= from; index--) {
            DailyBar bar = bars.get(index);
            BigDecimal candleBody = body(bar);
            BigDecimal range = bar.high().subtract(bar.low());
            if (range.signum() <= 0 || candleBody.signum() == 0) continue;
            BigDecimal upperShadow = bar.high().subtract(bodyHigh(bar));
            BigDecimal lowerShadow = bodyLow(bar).subtract(bar.low());
            boolean ideal = upperShadow.compareTo(candleBody.multiply(BigDecimal.valueOf(2))) >= 0
                    && lowerShadow.compareTo(candleBody) <= 0
                    && upperShadow.divide(range, 8, RoundingMode.HALF_UP).doubleValue() >= 0.60;
            if (!ideal) continue;
            Trend prior = trendBefore(bars, index, 5);
            boolean invertedHammer = prior == Trend.DOWN;
            boolean shootingStar = prior == Trend.UP;
            if (!invertedHammer && !shootingStar) continue;

            ConfirmationStatus status;
            String confirmation;
            if (index == bars.size() - 1) {
                status = ConfirmationStatus.AWAITING_CONFIRMATION;
                confirmation = "该形态需要下一期方向性收市价确认，当前尚无后续完整 K 线";
            } else {
                DailyBar next = bars.get(index + 1);
                boolean confirmed = invertedHammer
                        ? next.close().compareTo(bodyHigh(bar)) > 0
                        : next.close().compareTo(bodyLow(bar)) < 0;
                status = confirmed ? ConfirmationStatus.CONFIRMED : ConfirmationStatus.INVALIDATED;
                confirmation = confirmed
                        ? (invertedHammer ? "下一期收市价升破倒锤子线实体上沿，形成看涨确认"
                                : "下一期收市价跌破流星线实体下沿，形成看跌确认")
                        : "下一期未给出同方向确认，当前不把它作为已确认反转信号";
            }
            result.add(new PatternSignal(
                    invertedHammer ? "INVERTED_HAMMER" : "SHOOTING_STAR",
                    invertedHammer ? "倒锤子线" : "流星线", invertedHammer ? "Inverted hammer" : "Shooting star",
                    "单根反转", invertedHammer ? Direction.BULLISH : Direction.BEARISH,
                    bar.date(), bar.date(), status == ConfirmationStatus.CONFIRMED ? 85 : 70,
                    status == ConfirmationStatus.CONFIRMED ? "STRONG" : "MODERATE", status, true,
                    List.of("上影线至少为实体的 2 倍", "下影线不超过实体长度", "实体位于全幅价格区间下端"),
                    invertedHammer ? "轮廓出现于短期下降趋势之后" : "轮廓出现于短期上升趋势之后",
                    invertedHammer ? "下降段低位的试探性反攻" : "上涨段高位的供给压制", confirmation,
                    money(invertedHammer ? bar.low() : bar.high()),
                    invertedHammer ? "收市价有效跌破倒锤子线最低点，则看涨警告失效"
                            : "收市价有效升破流星线最高点，则看跌警告失效",
                    "第五章 星线"));
        }
        return result;
    }

    private static List<PatternSignal> detectMirroredTwoLineReversals(List<DailyBar> bars) {
        List<PatternSignal> result = new ArrayList<>();
        int from = Math.max(1, bars.size() - 15);
        for (int index = bars.size() - 1; index >= from; index--) {
            DailyBar first = bars.get(index - 1);
            DailyBar second = bars.get(index);
            Trend prior = trendBefore(bars, index - 1, 5);
            boolean bearishEngulfing = prior == Trend.UP && bullish(first) && bearish(second)
                    && second.open().compareTo(first.close()) >= 0
                    && second.close().compareTo(first.open()) <= 0;
            if (bearishEngulfing) {
                result.add(new PatternSignal(
                        "BEARISH_ENGULFING", "看跌吞没形态", "Bearish engulfing pattern", "反转",
                        Direction.BEARISH, first.date(), second.date(), 80, "STRONG", ConfirmationStatus.NOT_REQUIRED,
                        true, List.of("第一根为白色（收盘高于开盘）实体", "第二根为黑色（收盘低于开盘）实体",
                                "第二根实体完整包裹第一根实体"),
                        "形态之前的 5 期收盘趋势向上", "形态位于近期上涨段末端",
                        "第二根收市后形态完成；后续走弱可进一步验证",
                        money(first.high().max(second.high())), "收市价有效升破形态最高点，则看跌警告失效",
                        "第四章 反转形态"));
            }

            BigDecimal midpoint = first.open().add(first.close())
                    .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            boolean piercing = prior == Trend.DOWN && bearish(first) && longBody(first) && bullish(second)
                    && second.open().compareTo(first.low()) < 0
                    && second.close().compareTo(midpoint) > 0
                    && second.close().compareTo(first.open()) < 0;
            if (piercing) {
                result.add(new PatternSignal(
                        "PIERCING_PATTERN", "刺透形态", "Piercing pattern", "双线反转", Direction.BULLISH,
                        first.date(), second.date(), 88, "STRONG", ConfirmationStatus.CONFIRMED, true,
                        List.of("第一根为下降趋势中的长黑实体", "第二根开市价低于前一根最低价",
                                "第二根白实体收市价深深进入第一根实体，并升越其中点"),
                        "形态之前的 5 期收盘趋势向下", "形态位于近期下降段低位",
                        "第二根收市后形态完成；后续升破形态高点可进一步增强证据",
                        money(first.low().min(second.low())), "收市价有效跌破形态最低点，则看涨警告失效",
                        "第四章 反转形态"));
            }
        }
        return result;
    }

    private static List<PatternSignal> detectWindows(List<DailyBar> bars) {
        List<PatternSignal> result = new ArrayList<>();
        int from = Math.max(1, bars.size() - 15);
        for (int index = bars.size() - 1; index >= from; index--) {
            DailyBar previous = bars.get(index - 1);
            DailyBar current = bars.get(index);
            boolean upward = current.low().compareTo(previous.high()) > 0;
            boolean downward = current.high().compareTo(previous.low()) < 0;
            if (!upward && !downward) continue;
            Direction direction = upward ? Direction.BULLISH : Direction.BEARISH;
            BigDecimal invalidation = upward ? previous.high() : previous.low();
            result.add(new PatternSignal(
                    upward ? "UPWARD_WINDOW" : "DOWNWARD_WINDOW", upward ? "向上窗口" : "向下窗口",
                    upward ? "Rising window" : "Falling window", "持续/结构", direction,
                    previous.date(), current.date(), 70, "MODERATE", ConfirmationStatus.NOT_REQUIRED, true,
                    List.of(upward ? "当期最低价高于前一期最高价，完整价格区间未重叠"
                                    : "当期最高价低于前一期最低价，完整价格区间未重叠",
                            upward ? "窗口全区间转化为潜在支撑" : "窗口全区间转化为潜在阻挡"),
                    "窗口首先按持续信号处理，并结合主要趋势判断", upward ? "价格向上跳空" : "价格向下跳空",
                    "窗口在第二根 K 线形成后成立；回补过程按收市价验证", money(invalidation),
                    upward ? "收市价跌破窗口下边缘，则向上窗口支撑失效"
                            : "收市价升破窗口上边缘，则向下窗口阻挡失效",
                    "第七章 持续形态"));
        }
        return result;
    }

    private static List<PatternSignal> detectDarkCloudCover(List<DailyBar> bars) {
        List<PatternSignal> result = new ArrayList<>();
        int from = Math.max(1, bars.size() - 15);
        for (int index = bars.size() - 1; index >= from; index--) {
            DailyBar first = bars.get(index - 1);
            DailyBar second = bars.get(index);
            BigDecimal midpoint = first.open().add(first.close())
                    .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            boolean matches = trendBefore(bars, index - 1, 5) == Trend.UP
                    && bullish(first) && longBody(first) && bearish(second)
                    && second.open().compareTo(first.high()) > 0
                    && second.close().compareTo(midpoint) < 0
                    && second.close().compareTo(first.open()) > 0;
            if (!matches) continue;
            BigDecimal high = first.high().max(second.high());
            result.add(new PatternSignal(
                    "DARK_CLOUD_COVER", "乌云盖顶形态", "Dark-cloud cover", "双线反转",
                    Direction.BEARISH, first.date(), second.date(), 88, "STRONG", ConfirmationStatus.CONFIRMED,
                    true, List.of("第一根为上升趋势中的长白实体", "第二根开市价高于前一根最高价",
                            "第二根黑实体收市价深深进入第一根实体，并跌破其中点"),
                    "形态之前的 5 期收盘趋势向上", "形态位于近期上涨段高位",
                    "第二根收市后形态完成；后续跌破形态低点可进一步增强证据", money(high),
                    "收市价有效升破形态最高点，则看跌警告失效", "第四章 反转形态"));
        }
        return result;
    }

    private static List<PatternSignal> detectStarPatterns(List<DailyBar> bars) {
        List<PatternSignal> result = new ArrayList<>();
        int from = Math.max(2, bars.size() - 15);
        for (int index = bars.size() - 1; index >= from; index--) {
            DailyBar first = bars.get(index - 2);
            DailyBar star = bars.get(index - 1);
            DailyBar third = bars.get(index);
            BigDecimal firstBody = body(first);
            BigDecimal starBody = body(star);
            if (!longBody(first) || starBody.compareTo(firstBody.multiply(BigDecimal.valueOf(0.35))) > 0) continue;
            BigDecimal midpoint = first.open().add(first.close())
                    .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            Trend prior = trendBefore(bars, index - 2, 5);
            boolean evening = prior == Trend.UP && bullish(first) && bearish(third)
                    && bodyLow(star).compareTo(bodyHigh(first)) > 0
                    && third.close().compareTo(midpoint) < 0;
            boolean morning = prior == Trend.DOWN && bearish(first) && bullish(third)
                    && bodyHigh(star).compareTo(bodyLow(first)) < 0
                    && third.close().compareTo(midpoint) > 0;
            if (!evening && !morning) continue;
            Direction direction = morning ? Direction.BULLISH : Direction.BEARISH;
            BigDecimal invalidation = morning
                    ? first.low().min(star.low()).min(third.low())
                    : first.high().max(star.high()).max(third.high());
            result.add(new PatternSignal(
                    morning ? "MORNING_STAR" : "EVENING_STAR", morning ? "启明星形态" : "黄昏星形态",
                    morning ? "Morning star" : "Evening star", "星线反转", direction,
                    first.date(), third.date(), 92, "VERY_STRONG", ConfirmationStatus.CONFIRMED, true,
                    List.of(morning ? "第一根为下降趋势中的长黑实体" : "第一根为上升趋势中的长白实体",
                            "第二根小实体与第一根实体之间形成价格跳空",
                            morning ? "第三根长白实体收市价向上穿越第一根实体中点"
                                    : "第三根长黑实体收市价向下穿越第一根实体中点"),
                    morning ? "形态出现于明确的短期下降趋势之后" : "形态出现于明确的短期上升趋势之后",
                    morning ? "三根线在下降段低位完成" : "三根线在上涨段高位完成",
                    "第三根蜡烛线收市后形态完成，并构成方向确认", money(invalidation),
                    morning ? "收市价有效跌破三根线最低点，则看涨警告失效"
                            : "收市价有效升破三根线最高点，则看跌警告失效",
                    "第五章 星线"));
        }
        return result;
    }

    private static List<PatternSignal> detectBullishEngulfing(List<DailyBar> bars) {
        List<PatternSignal> result = new ArrayList<>();
        int from = Math.max(1, bars.size() - 15);
        for (int index = bars.size() - 1; index >= from; index--) {
            DailyBar first = bars.get(index - 1);
            DailyBar second = bars.get(index);
            if (trendBefore(bars, index - 1, 5) != Trend.DOWN || !bearish(first) || !bullish(second)) {
                continue;
            }
            if (second.open().compareTo(first.close()) > 0 || second.close().compareTo(first.open()) < 0) {
                continue;
            }
            BigDecimal low = first.low().min(second.low());
            result.add(new PatternSignal(
                    "BULLISH_ENGULFING", "看涨吞没形态", "Bullish engulfing pattern", "反转",
                    Direction.BULLISH, first.date(), second.date(), 80, "STRONG", ConfirmationStatus.NOT_REQUIRED,
                    true,
                    List.of("第一根为黑色（收盘低于开盘）实体", "第二根为白色（收盘高于开盘）实体",
                            "第二根实体完整包裹第一根实体"),
                    "形态之前的 5 期收盘趋势向下", "形态位于近期下降段末端",
                    "第二根收市后形态完成；后续走强可进一步验证", money(low),
                    "收市价有效跌破形态最低点，则看涨警告失效", "第四章 反转形态"));
        }
        return List.copyOf(result);
    }

    private static List<PatternSignal> detectUmbrellaLines(List<DailyBar> bars) {
        List<PatternSignal> result = new ArrayList<>();
        int from = Math.max(5, bars.size() - 15);
        for (int index = bars.size() - 1; index >= from; index--) {
            DailyBar bar = bars.get(index);
            BigDecimal body = bar.close().subtract(bar.open()).abs();
            BigDecimal range = bar.high().subtract(bar.low());
            if (range.signum() <= 0 || body.signum() == 0) continue;
            BigDecimal lowerShadow = bar.open().min(bar.close()).subtract(bar.low());
            BigDecimal upperShadow = bar.high().subtract(bar.open().max(bar.close()));
            boolean ideal = lowerShadow.compareTo(body.multiply(BigDecimal.valueOf(2))) >= 0
                    && upperShadow.compareTo(body) <= 0
                    && bar.open().min(bar.close()).subtract(bar.low())
                            .divide(range, 8, RoundingMode.HALF_UP).doubleValue() >= 0.60;
            if (!ideal) continue;

            Trend prior = trendBefore(bars, index, 5);
            boolean hammer = prior == Trend.DOWN;
            boolean hangingMan = prior == Trend.UP;
            if (!hammer && !hangingMan) continue;
            ConfirmationStatus confirmation = ConfirmationStatus.NOT_REQUIRED;
            String confirmationEvidence = "锤子线本身构成警告；后续上涨可增强证据";
            if (hangingMan) {
                if (index == bars.size() - 1) {
                    confirmation = ConfirmationStatus.AWAITING_CONFIRMATION;
                    confirmationEvidence = "上吊线需要下一期看跌价格行为确认，当前尚无后续完整 K 线";
                } else if (bars.get(index + 1).close().compareTo(bar.open().min(bar.close())) < 0) {
                    confirmation = ConfirmationStatus.CONFIRMED;
                    confirmationEvidence = "下一期收市价跌破上吊线实体下沿，形成看跌确认";
                } else {
                    confirmation = ConfirmationStatus.INVALIDATED;
                    confirmationEvidence = "下一期未给出看跌确认，当前不把它作为已确认反转信号";
                }
            }
            result.add(new PatternSignal(
                    hammer ? "HAMMER" : "HANGING_MAN", hammer ? "锤子线" : "上吊线",
                    hammer ? "Hammer" : "Hanging man", "单根反转", hammer ? Direction.BULLISH : Direction.BEARISH,
                    bar.date(), bar.date(), hangingMan && confirmation == ConfirmationStatus.CONFIRMED ? 85 : 75,
                    hangingMan && confirmation == ConfirmationStatus.CONFIRMED ? "STRONG" : "MODERATE", confirmation,
                    true, List.of("下影线至少为实体的 2 倍", "上影线不超过实体长度", "实体位于全幅价格区间上端"),
                    hammer ? "轮廓出现于短期下降趋势之后" : "轮廓出现于短期上升趋势之后",
                    hammer ? "下降段低位的潜在需求反攻" : "上涨段高位的潜在供给警告", confirmationEvidence,
                    money(hammer ? bar.low() : bar.high()),
                    hammer ? "收市价有效跌破锤子线最低点，则看涨警告失效"
                            : "收市价有效升破上吊线最高点，则看跌警告失效",
                    "第四章 反转形态"));
        }
        return result;
    }

    private static List<PriceLevel> priceLevels(List<DailyBar> bars, PatternSignal primary) {
        if (primary == null) return List.copyOf(windowLevels(bars));
        List<DailyBar> recent = bars.subList(Math.max(0, bars.size() - 20), bars.size());
        BigDecimal swingLow = recent.stream().map(DailyBar::low).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal swingHigh = recent.stream().map(DailyBar::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal latest = bars.getLast().close();
        List<PriceLevel> levels = new ArrayList<>();
        if (primary.direction() == Direction.BULLISH) {
            levels.add(new PriceLevel(LevelRole.SUPPORT, primary.invalidationPrice(), primary.invalidationPrice(),
                    primary.name() + "形态低点", latest.compareTo(primary.invalidationPrice()) >= 0
                    ? LevelStatus.ACTIVE : LevelStatus.BROKEN, "以收市价是否跌破判断有效性"));
            if (swingHigh.compareTo(latest) > 0) {
                levels.add(new PriceLevel(LevelRole.RESISTANCE, money(swingHigh), money(swingHigh),
                        "最近 20 期结构高点", LevelStatus.ACTIVE, "以收市价是否升破判断有效性"));
            }
        } else if (primary.direction() == Direction.BEARISH) {
            levels.add(new PriceLevel(LevelRole.RESISTANCE, primary.invalidationPrice(), primary.invalidationPrice(),
                    primary.name() + "形态高点", latest.compareTo(primary.invalidationPrice()) <= 0
                    ? LevelStatus.ACTIVE : LevelStatus.BROKEN, "以收市价是否升破判断有效性"));
            if (swingLow.compareTo(latest) < 0) {
                levels.add(new PriceLevel(LevelRole.SUPPORT, money(swingLow), money(swingLow),
                        "最近 20 期结构低点", LevelStatus.ACTIVE, "以收市价是否跌破判断有效性"));
            }
        }
        levels.addAll(windowLevels(bars));
        return List.copyOf(levels);
    }

    private static List<PriceLevel> windowLevels(List<DailyBar> bars) {
        List<PriceLevel> levels = new ArrayList<>();
        int from = Math.max(1, bars.size() - 15);
        for (int index = bars.size() - 1; index >= from; index--) {
            DailyBar previous = bars.get(index - 1);
            DailyBar current = bars.get(index);
            if (current.low().compareTo(previous.high()) > 0) {
                BigDecimal lower = money(previous.high());
                BigDecimal upper = money(current.low());
                levels.add(new PriceLevel(LevelRole.SUPPORT, lower, upper,
                        "向上窗口 " + previous.date() + "—" + current.date(),
                        bars.subList(index + 1, bars.size()).stream().anyMatch(bar -> bar.close().compareTo(lower) < 0)
                                ? LevelStatus.BROKEN : LevelStatus.ACTIVE,
                        "收市价跌破窗口下边缘时失效"));
            } else if (current.high().compareTo(previous.low()) < 0) {
                BigDecimal lower = money(current.high());
                BigDecimal upper = money(previous.low());
                levels.add(new PriceLevel(LevelRole.RESISTANCE, lower, upper,
                        "向下窗口 " + previous.date() + "—" + current.date(),
                        bars.subList(index + 1, bars.size()).stream().anyMatch(bar -> bar.close().compareTo(upper) > 0)
                                ? LevelStatus.BROKEN : LevelStatus.ACTIVE,
                        "收市价升破窗口上边缘时失效"));
            }
        }
        return levels;
    }

    private static ConfluenceAssessment confluence(
            List<DailyBar> bars, TrendContext trend, PatternSignal primary, List<PriceLevel> levels) {
        if (primary == null) {
            return new ConfluenceAssessment(Direction.NEUTRAL, 0, "NO_SIGNAL", List.of(),
                    "最近 15 期没有仍有效的候选形态；未命中或已失效的信号不能形成方向结论");
        }
        Direction direction = primary.direction();
        List<ConfluenceFactor> factors = new ArrayList<>();
        boolean confirmed = primary.confirmationStatus() == ConfirmationStatus.CONFIRMED
                || primary.confirmationStatus() == ConfirmationStatus.NOT_REQUIRED;
        factors.add(new ConfluenceFactor(EvidenceKind.CANDLESTICK, primary.name(), direction, confirmed, 40,
                confirmed ? String.join("；", primary.constructionEvidence()) : "形态仍待后续确认，不计入同向分"));

        boolean trendAligned = direction == Direction.BULLISH
                ? trend.shortTerm() == Trend.UP : trend.shortTerm() == Trend.DOWN;
        factors.add(new ConfluenceFactor(EvidenceKind.TREND, "短期趋势", trendDirection(trend.shortTerm()),
                trendAligned, 15, "最近 5 期收盘变化 " + trend.shortReturnPercent() + "%"));

        double rsi = rsi(bars, 14);
        Direction momentumDirection = rsi >= 70 ? Direction.BEARISH : rsi <= 30 ? Direction.BULLISH : Direction.NEUTRAL;
        factors.add(new ConfluenceFactor(EvidenceKind.MOMENTUM, "RSI 14", momentumDirection,
                momentumDirection == direction, 15, "RSI=" + decimal(rsi) + "，阈值 30/70"));

        DailyBar latest = bars.getLast();
        double averageVolume = bars.subList(Math.max(0, bars.size() - 20), bars.size()).stream()
                .map(DailyBar::volumeShares).filter(Objects::nonNull).mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double volumeRatio = averageVolume == 0 || latest.volumeShares() == null
                ? 0 : latest.volumeShares().doubleValue() / averageVolume;
        Direction volumeDirection = bullish(latest) ? Direction.BULLISH : bearish(latest) ? Direction.BEARISH : Direction.NEUTRAL;
        boolean volumeAligned = volumeRatio >= 1.2 && volumeDirection == direction;
        factors.add(new ConfluenceFactor(EvidenceKind.VOLUME, "20 期量比", volumeDirection,
                volumeAligned, 15, "当期成交量/20 期均量=" + decimal(volumeRatio)));

        boolean levelAligned = !levels.isEmpty() && levels.getFirst().status() != LevelStatus.BROKEN;
        factors.add(new ConfluenceFactor(EvidenceKind.LEVEL, "形态支撑/阻挡", direction, levelAligned, 15,
                levelAligned ? levels.getFirst().origin() + "仍有效" : "形态失效位已被收市价突破"));

        int score = factors.stream().filter(ConfluenceFactor::aligned).mapToInt(ConfluenceFactor::weight).sum();
        String grade = score >= 80 ? "STRONG" : score >= 60 ? "MODERATE" : "WEAK";
        return new ConfluenceAssessment(direction, score, grade, factors,
                "共 " + factors.stream().filter(ConfluenceFactor::aligned).count() + "/" + factors.size()
                        + " 类证据同向；分数是证据覆盖度，不是成功概率");
    }

    private static RiskAssessment risk(List<DailyBar> bars, PatternSignal primary, List<PriceLevel> levels) {
        BigDecimal entry = money(bars.getLast().close());
        if (primary == null) {
            return new RiskAssessment(Direction.NEUTRAL, entry, null, null, null, null,
                    "INSUFFICIENT", List.of("最近窗口未检出可用于定义失效位的形态"));
        }
        BigDecimal invalidation = primary.invalidationPrice();
        BigDecimal risk = money(entry.subtract(invalidation).abs());
        BigDecimal target = levels.stream()
                .filter(level -> primary.direction() == Direction.BULLISH
                        ? level.role() == LevelRole.RESISTANCE && level.lower().compareTo(entry) > 0
                        : level.role() == LevelRole.SUPPORT && level.upper().compareTo(entry) < 0)
                .map(level -> primary.direction() == Direction.BULLISH ? level.lower() : level.upper())
                .findFirst().orElse(null);
        BigDecimal ratio = target == null || risk.signum() == 0 ? null
                : target.subtract(entry).abs().divide(risk, 2, RoundingMode.HALF_UP);
        String quality = ratio == null ? "NO_TARGET" : ratio.compareTo(BigDecimal.valueOf(2)) >= 0
                ? "FAVORABLE" : ratio.compareTo(BigDecimal.ONE) >= 0 ? "MARGINAL" : "UNFAVORABLE";
        List<String> notes = new ArrayList<>();
        notes.add("失效位来自形态极值，并以收市价有效突破作为判据");
        notes.add(target == null
                ? "未找到方向上可用的结构目标，不能计算风险报偿比"
                : "目标参考来自最近 20 期结构支撑/阻挡，不是蜡烛图自身给出的价格目标");
        notes.add("风险报偿仅描述图表结构，不代表应建立头寸");
        return new RiskAssessment(primary.direction(), entry, invalidation, risk, money(target), ratio, quality, notes);
    }

    private static Direction trendDirection(Trend trend) {
        return trend == Trend.UP ? Direction.BULLISH : trend == Trend.DOWN ? Direction.BEARISH : Direction.NEUTRAL;
    }

    private static double rsi(List<DailyBar> bars, int period) {
        int start = Math.max(1, bars.size() - period);
        double gains = 0;
        double losses = 0;
        int observations = 0;
        for (int index = start; index < bars.size(); index++) {
            double change = bars.get(index).close().doubleValue() - bars.get(index - 1).close().doubleValue();
            if (change > 0) gains += change;
            if (change < 0) losses -= change;
            observations++;
        }
        if (observations == 0) return 50;
        double averageGain = gains / observations;
        double averageLoss = losses / observations;
        if (averageLoss == 0) return 100;
        double rs = averageGain / averageLoss;
        return 100 - (100 / (1 + rs));
    }

    private static BigDecimal body(DailyBar bar) { return bar.close().subtract(bar.open()).abs(); }
    private static BigDecimal bodyLow(DailyBar bar) { return bar.open().min(bar.close()); }
    private static BigDecimal bodyHigh(DailyBar bar) { return bar.open().max(bar.close()); }

    private static boolean longBody(DailyBar bar) {
        BigDecimal range = bar.high().subtract(bar.low());
        return range.signum() > 0 && body(bar).divide(range, 8, RoundingMode.HALF_UP)
                .compareTo(BigDecimal.valueOf(0.55)) >= 0;
    }

    private static TrendContext trendContext(List<DailyBar> bars) {
        Trend shortTrend = trendBefore(bars, bars.size(), 5);
        Trend primaryTrend = trendBefore(bars, bars.size(), 20);
        BigDecimal latest = bars.getLast().close();
        BigDecimal shortStart = bars.get(bars.size() - 5).close();
        BigDecimal shortReturn = percent(latest, shortStart);
        BigDecimal sma20 = bars.subList(bars.size() - 20, bars.size()).stream()
                .map(DailyBar::close).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(20), 8, RoundingMode.HALF_UP);
        return new TrendContext(shortTrend, primaryTrend, shortReturn, percent(latest, sma20),
                "短期按 5 期收盘变化、主要趋势按 20 期收盘变化判定；横盘阈值分别为 ±1% 与 ±3%");
    }

    private static Trend trendBefore(List<DailyBar> bars, int exclusiveIndex, int lookback) {
        if (exclusiveIndex < lookback) return Trend.INSUFFICIENT;
        BigDecimal start = bars.get(exclusiveIndex - lookback).close();
        BigDecimal end = bars.get(exclusiveIndex - 1).close();
        double change = end.doubleValue() / start.doubleValue() - 1;
        double threshold = lookback <= 5 ? 0.01 : 0.03;
        return change > threshold ? Trend.UP : change < -threshold ? Trend.DOWN : Trend.SIDEWAYS;
    }

    private static boolean bullish(DailyBar bar) { return bar.close().compareTo(bar.open()) > 0; }
    private static boolean bearish(DailyBar bar) { return bar.close().compareTo(bar.open()) < 0; }

    private static BigDecimal percent(BigDecimal value, BigDecimal base) {
        if (base.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return value.divide(base, 8, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private Methodology methodology(List<PatternSignal> signals) {
        Map<String, String> thresholds = new LinkedHashMap<>();
        thresholds.put("短期趋势", "5 期收盘变化超过 ±1%");
        thresholds.put("主要趋势", "20 期收盘变化超过 ±3%");
        thresholds.put("实体包裹", "第二根实体上下边界覆盖第一根实体");
        thresholds.put("长实体", "实体至少占当期最高价—最低价区间的 55%");
        thresholds.put("伞形线/上影反转", "主影线至少为实体 2 倍，短影线不超过实体");
        thresholds.put("星线小实体", "第二根实体不超过第一根实体的 35%");
        thresholds.put("十字线", "实体不超过当期最高价—最低价区间的 5%");
        thresholds.put("窗口", "相邻两期完整最高—最低价格区间不重叠");
        thresholds.put("成交量确认", "当期成交量至少为最近 20 期均量的 1.2 倍");
        thresholds.put("识别窗口", "扫描最近 15 根完整 K 线；结构位回看 20 根");
        thresholds.put("孕线", "第二根实体严格位于第一根长实体内且不超过其 35%；颜色不限、影线可越界");
        thresholds.put("孕线确认", "后续完整 K 线收市价突破两根线方向性极值；反向突破失效位则失效");
        thresholds.put("形态状态", "等待确认不计形态同向分；形成后收盘突破失效位则失效，不自动恢复");
        return new Methodology("NISON-CANDLESTICK-1.1", ANALYSIS_SEQUENCE,
                List.of(new BookReference("第三章 蜡烛图的绘制方法", "开、高、低、收与实体、影线"),
                        new BookReference("第四章 反转形态", "前置趋势、伞形线、吞没、刺透、乌云盖顶与风险报偿"),
                        new BookReference("第五章 星线", "启明星、黄昏星、流星与倒锤子线的确认"),
                        new BookReference("第六章 其他反转形态", "孕线与十字孕线的实体包裹、趋势和确认"),
                        new BookReference("第七章 持续形态", "窗口及其支撑、阻挡和收市价失效规则"),
                        new BookReference("第八章 神奇的十字线", "十字线的趋势位置与后续确认"),
                        new BookReference("第九章 蜡烛图技术汇总", "总体环境、确认与主观边界"),
                        new BookReference("第十章 蜡烛图信号的汇聚", "支撑阻挡与信号汇聚"),
                        new BookReference("第十一章 蜡烛图与趋势线", "支撑阻挡、极性转换与收市价突破"),
                        new BookReference("第十三章 蜡烛图与移动平均线", "主要趋势与移动平均线"),
                        new BookReference("第十四章 蜡烛图与摆动指数", "RSI 与超买超卖验证"),
                        new BookReference("第十五章 蜡烛图与交易量", "成交量验证"),
                        new BookReference("第十六章 测算价格目标", "价格目标来自独立西方工具"),
                        new BookReference("第十七章 东西方技术珠联璧合", "多技术相互验证")),
                thresholds, "0—100 分表示规则证据覆盖度，不是方向发生概率或历史胜率",
                knowledge.forCandlestick(signals.stream().map(PatternSignal::id).toList()));
    }
}
