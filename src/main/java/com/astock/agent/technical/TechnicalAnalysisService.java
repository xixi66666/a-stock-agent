package com.astock.agent.technical;

import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.SectionStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.CCIIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.ROCIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.WilliamsRIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

public final class TechnicalAnalysisService {

    private final BarSeriesFactory seriesFactory;

    public TechnicalAnalysisService(BarSeriesFactory seriesFactory) {
        this.seriesFactory = seriesFactory;
    }

    public TechnicalSnapshot analyze(List<DailyBar> dailyBars, Timeframe timeframe) {
        List<DailyBar> bars = seriesFactory.aggregate(dailyBars, timeframe);
        if (bars.size() < 20) {
            throw new IllegalArgumentException("At least 20 bars are required for technical analysis");
        }
        BarSeries series = seriesFactory.toSeries(bars, timeframe.name().toLowerCase());
        int end = series.getEndIndex();
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        double latestClose = value(close, end);
        List<IndicatorCard> cards = new ArrayList<>();

        for (int period : List.of(5, 10, 20, 30, 60, 120, 250)) {
            SMAIndicator sma = new SMAIndicator(close, period);
            double current = value(sma, end);
            cards.add(compareCard("SMA_" + period, "趋势", "SMA " + period,
                    Map.of("period", period), current, "元", latestClose, "收盘价", series(sma, end), bars));
        }
        for (int period : List.of(20, 60)) {
            EMAIndicator ema = new EMAIndicator(close, period);
            cards.add(compareCard("EMA_" + period, "趋势", "EMA " + period,
                    Map.of("period", period), value(ema, end), "元", latestClose, "收盘价", series(ema, end), bars));
        }
        double sma20 = value(new SMAIndicator(close, 20), end);
        double bias = (latestClose / sma20 - 1) * 100;
        cards.add(signedCard("BIAS_20", "趋势", "BIAS 20", Map.of("period", 20), bias, "%",
                "BIAS=(收盘价/SMA20-1)", List.of(bias), bars));

        MACDIndicator macd = new MACDIndicator(close, 12, 26);
        double macdValue = value(macd, end);
        double signalValue = value(macd.getSignalLine(9), end);
        cards.add(card("MACD_12_26_9", "动量", "MACD",
                Map.of("fast", 12, "slow", 26, "signal", 9), macdValue - signalValue, "",
                macdValue >= signalValue ? IndicatorState.STRONG : IndicatorState.WEAK,
                "DIF " + format(macdValue) + (macdValue >= signalValue ? " >= " : " < ")
                        + "DEA " + format(signalValue), series(macd, end), bars));

        for (int period : List.of(6, 12, 24)) {
            RSIIndicator rsi = new RSIIndicator(close, period);
            double current = value(rsi, end);
            IndicatorState state = current >= 70 ? IndicatorState.OVERBOUGHT
                    : current <= 30 ? IndicatorState.OVERSOLD : IndicatorState.NEUTRAL;
            cards.add(card("RSI_" + period, "动量", "RSI " + period, Map.of("period", period),
                    current, "", state, "RSI " + format(current) + "，阈值 30/70", series(rsi, end), bars));
        }

        List<Double> stochastic = stochasticSeries(bars, 9);
        double k = last(stochastic);
        double d = averageLast(stochastic, 3);
        double j = 3 * k - 2 * d;
        cards.add(card("KDJ_9_3_3", "动量", "KDJ", Map.of("period", 9, "k", 3, "d", 3),
                j, "", j > 80 ? IndicatorState.OVERBOUGHT : j < 20 ? IndicatorState.OVERSOLD : IndicatorState.NEUTRAL,
                "K=" + format(k) + "，D=" + format(d) + "，J=" + format(j), tail(stochastic, 30), bars));

        CCIIndicator cci = new CCIIndicator(series, 20);
        cards.add(thresholdCard("CCI_20", "动量", "CCI 20", Map.of("period", 20), value(cci, end), "",
                -100, 100, series(cci, end), bars));
        ROCIndicator roc = new ROCIndicator(close, 12);
        cards.add(signedCard("ROC_12", "动量", "ROC 12", Map.of("period", 12), value(roc, end), "%",
                "ROC 与 12 期前收盘价比较", series(roc, end), bars));
        WilliamsRIndicator williams = new WilliamsRIndicator(series, 14);
        double wr = value(williams, end);
        cards.add(card("WILLIAMS_R_14", "动量", "Williams %R", Map.of("period", 14), wr, "%",
                wr > -20 ? IndicatorState.OVERBOUGHT : wr < -80 ? IndicatorState.OVERSOLD : IndicatorState.NEUTRAL,
                "%R " + format(wr) + "，阈值 -80/-20", series(williams, end), bars));
        List<Double> closes = bars.stream().map(bar -> bar.close().doubleValue()).toList();
        double psy = CustomIndicators.psy(closes, 12);
        cards.add(thresholdCard("PSY_12", "动量", "PSY 12", Map.of("period", 12), psy, "%",
                25, 75, List.of(psy), bars));

        ATRIndicator atr = new ATRIndicator(series, 14);
        double atrValue = value(atr, end);
        cards.add(card("ATR_14", "波动", "ATR 14", Map.of("period", 14), atrValue, "元",
                IndicatorState.NEUTRAL, "ATR 表示最近 14 期真实波幅均值", series(atr, end), bars));
        double natr = atrValue / latestClose * 100;
        cards.add(card("NATR_14", "波动", "NATR 14", Map.of("period", 14), natr, "%",
                natr >= 3 ? IndicatorState.EXPANDING : IndicatorState.CONTRACTING,
                "NATR=ATR/收盘价，当前 " + format(natr) + "%", List.of(natr), bars));
        List<Double> closes20 = tail(closes, 20);
        double mean20 = CustomIndicators.mean(closes20);
        double std20 = CustomIndicators.standardDeviation(closes20);
        double bollWidth = mean20 == 0 ? 0 : std20 * 4 / mean20 * 100;
        cards.add(card("BOLL_WIDTH_20_2", "波动", "布林带宽", Map.of("period", 20, "deviation", 2),
                bollWidth, "%", bollWidth > 10 ? IndicatorState.EXPANDING : IndicatorState.CONTRACTING,
                "带宽=(上轨-下轨)/中轨，当前 " + format(bollWidth) + "%", List.of(bollWidth), bars));
        List<Double> returns = returns(closes);
        double historicalVolatility = CustomIndicators.standardDeviation(tail(returns, 20)) * Math.sqrt(250) * 100;
        cards.add(card("HISTORICAL_VOLATILITY_20", "波动", "20 日历史波动率", Map.of("period", 20),
                historicalVolatility, "%", historicalVolatility > 30 ? IndicatorState.EXPANDING : IndicatorState.CONTRACTING,
                "日收益标准差年化，当前 " + format(historicalVolatility) + "%", List.of(historicalVolatility), bars));
        double donchian = donchianWidth(bars, 20);
        cards.add(card("DONCHIAN_WIDTH_20", "波动", "唐奇安通道宽度", Map.of("period", 20), donchian, "%",
                donchian > 15 ? IndicatorState.EXPANDING : IndicatorState.CONTRACTING,
                "20 期最高价与最低价区间占收盘价 " + format(donchian) + "%", List.of(donchian), bars));

        List<Double> volumes = bars.stream().map(bar -> number(bar.volumeShares())).toList();
        double volumeRatio = last(volumes) / averageLast(volumes, 20);
        cards.add(card("VOLUME_RATIO_20", "量价", "20 期量比", Map.of("period", 20), volumeRatio, "x",
                volumeRatio >= 1.2 ? IndicatorState.STRONG : volumeRatio <= 0.8 ? IndicatorState.WEAK : IndicatorState.NEUTRAL,
                "当期成交量/20 期均量=" + format(volumeRatio), List.of(volumeRatio), bars));
        double obv = obv(bars);
        cards.add(signedCard("OBV", "量价", "OBV", Map.of(), obv, "股", "涨跌方向累计成交量", List.of(obv), bars));
        double mfi = moneyFlowIndex(bars, 14);
        cards.add(thresholdCard("MFI_14", "量价", "MFI 14", Map.of("period", 14), mfi, "",
                20, 80, List.of(mfi), bars));
        double cmf = chaikinMoneyFlow(bars, 20);
        cards.add(signedCard("CMF_20", "量价", "Chaikin Money Flow", Map.of("period", 20), cmf, "",
                "20 期资金流量乘数加权", List.of(cmf), bars));

        for (int period : List.of(20, 60, 120, 250)) {
            double periodReturn = periodReturn(closes, period) * 100;
            cards.add(signedCard("RETURN_" + period, "相对强弱与风险", period + " 期收益",
                    Map.of("period", period), periodReturn, "%", "当前收盘价与期初收盘价比较",
                    List.of(periodReturn), bars));
        }
        double maxDrawdown = maxDrawdown(closes) * 100;
        cards.add(card("MAX_DRAWDOWN", "相对强弱与风险", "最大回撤", Map.of(), maxDrawdown, "%",
                maxDrawdown <= -20 ? IndicatorState.WEAK : IndicatorState.NEUTRAL,
                "历史峰值到后续谷值的最大跌幅 " + format(maxDrawdown) + "%", List.of(maxDrawdown), bars));
        double returnMean = CustomIndicators.mean(returns);
        double returnStd = CustomIndicators.standardDeviation(returns);
        double sharpe = returnStd == 0 ? 0 : returnMean / returnStd * Math.sqrt(250);
        cards.add(signedCard("SHARPE", "相对强弱与风险", "Sharpe", Map.of("annualization", 250), sharpe, "",
                "无风险利率按 0 的学习口径年化", List.of(sharpe), bars));
        double var = CustomIndicators.historicalVar(returns, 0.95) * 100;
        double cvar = CustomIndicators.historicalCvar(returns, 0.95) * 100;
        cards.add(card("VAR_95", "相对强弱与风险", "历史 VaR 95%", Map.of("confidence", 95), var, "%",
                IndicatorState.NEUTRAL, "历史收益最差 5% 分位=" + format(var) + "%", List.of(var), bars));
        cards.add(card("CVAR_95", "相对强弱与风险", "历史 CVaR 95%", Map.of("confidence", 95), cvar, "%",
                IndicatorState.NEUTRAL, "VaR 尾部收益均值=" + format(cvar) + "%", List.of(cvar), bars));
        double skew = CustomIndicators.skewness(returns);
        double kurtosis = CustomIndicators.kurtosis(returns);
        cards.add(signedCard("SKEWNESS", "相对强弱与风险", "收益偏度", Map.of(), skew, "",
                "收益分布三阶标准矩", List.of(skew), bars));
        cards.add(card("KURTOSIS", "相对强弱与风险", "超额峰度", Map.of(), kurtosis, "",
                kurtosis > 0 ? IndicatorState.WEAK : IndicatorState.NEUTRAL,
                "收益分布四阶标准矩减 3=" + format(kurtosis), List.of(kurtosis), bars));

        return new TechnicalSnapshot(timeframe, bars.getLast().date(), cards);
    }

    private static IndicatorCard compareCard(String id, String group, String name, Map<String, Integer> params,
            double indicatorValue, String unit, double comparison, String comparisonName, List<Double> values,
            List<DailyBar> bars) {
        boolean strong = comparison >= indicatorValue;
        return card(id, group, name, params, indicatorValue, unit,
                strong ? IndicatorState.STRONG : IndicatorState.WEAK,
                comparisonName + " " + format(comparison) + (strong ? " >= " : " < ") + name + " "
                        + format(indicatorValue), values, bars);
    }

    private static IndicatorCard signedCard(String id, String group, String name, Map<String, Integer> params,
            double value, String unit, String formula, List<Double> values, List<DailyBar> bars) {
        return card(id, group, name, params, value, unit,
                value > 0 ? IndicatorState.STRONG : value < 0 ? IndicatorState.WEAK : IndicatorState.NEUTRAL,
                formula + "；当前值 " + format(value), values, bars);
    }

    private static IndicatorCard thresholdCard(String id, String group, String name, Map<String, Integer> params,
            double value, String unit, double lower, double upper, List<Double> values, List<DailyBar> bars) {
        IndicatorState state = value >= upper ? IndicatorState.OVERBOUGHT
                : value <= lower ? IndicatorState.OVERSOLD : IndicatorState.NEUTRAL;
        return card(id, group, name, params, value, unit, state,
                name + " " + format(value) + "，阈值 " + lower + "/" + upper, values, bars);
    }

    private static IndicatorCard card(String id, String group, String name, Map<String, Integer> params,
            double value, String unit, IndicatorState state, String trigger, List<Double> values, List<DailyBar> bars) {
        return new IndicatorCard(id, group, name, params, finite(value), unit, state, trigger,
                tail(values, 30), bars.getLast().date(), SectionStatus.HEALTHY);
    }

    private static double value(Indicator<Num> indicator, int index) {
        return indicator.getValue(index).doubleValue();
    }

    private static List<Double> series(Indicator<Num> indicator, int end) {
        List<Double> values = new ArrayList<>();
        for (int i = Math.max(0, end - 29); i <= end; i++) {
            values.add(finite(value(indicator, i)));
        }
        return values;
    }

    private static List<Double> stochasticSeries(List<DailyBar> bars, int period) {
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            int from = Math.max(0, i - period + 1);
            double high = bars.subList(from, i + 1).stream().mapToDouble(bar -> bar.high().doubleValue()).max().orElse(0);
            double low = bars.subList(from, i + 1).stream().mapToDouble(bar -> bar.low().doubleValue()).min().orElse(0);
            double close = bars.get(i).close().doubleValue();
            result.add(high == low ? 50 : (close - low) / (high - low) * 100);
        }
        return result;
    }

    private static List<Double> returns(List<Double> closes) {
        List<Double> result = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            result.add(closes.get(i) / closes.get(i - 1) - 1);
        }
        return result;
    }

    private static double donchianWidth(List<DailyBar> bars, int period) {
        List<DailyBar> window = bars.subList(Math.max(0, bars.size() - period), bars.size());
        double high = window.stream().mapToDouble(bar -> bar.high().doubleValue()).max().orElse(0);
        double low = window.stream().mapToDouble(bar -> bar.low().doubleValue()).min().orElse(0);
        return (high - low) / bars.getLast().close().doubleValue() * 100;
    }

    private static double obv(List<DailyBar> bars) {
        double total = 0;
        for (int i = 1; i < bars.size(); i++) {
            double volume = number(bars.get(i).volumeShares());
            int comparison = bars.get(i).close().compareTo(bars.get(i - 1).close());
            total += comparison > 0 ? volume : comparison < 0 ? -volume : 0;
        }
        return total;
    }

    private static double moneyFlowIndex(List<DailyBar> bars, int period) {
        int from = Math.max(1, bars.size() - period);
        double positive = 0;
        double negative = 0;
        for (int i = from; i < bars.size(); i++) {
            double typical = typical(bars.get(i));
            double previous = typical(bars.get(i - 1));
            double flow = typical * number(bars.get(i).volumeShares());
            if (typical >= previous) positive += flow; else negative += flow;
        }
        return negative == 0 ? 100 : 100 - 100 / (1 + positive / negative);
    }

    private static double chaikinMoneyFlow(List<DailyBar> bars, int period) {
        List<DailyBar> window = bars.subList(Math.max(0, bars.size() - period), bars.size());
        double flow = 0;
        double volume = 0;
        for (DailyBar bar : window) {
            double range = bar.high().doubleValue() - bar.low().doubleValue();
            double v = number(bar.volumeShares());
            if (range != 0) {
                flow += ((bar.close().doubleValue() - bar.low().doubleValue())
                        - (bar.high().doubleValue() - bar.close().doubleValue())) / range * v;
            }
            volume += v;
        }
        return volume == 0 ? 0 : flow / volume;
    }

    private static double periodReturn(List<Double> closes, int period) {
        int from = Math.max(0, closes.size() - 1 - period);
        return closes.getLast() / closes.get(from) - 1;
    }

    private static double maxDrawdown(List<Double> closes) {
        double peak = closes.getFirst();
        double drawdown = 0;
        for (double close : closes) {
            peak = Math.max(peak, close);
            drawdown = Math.min(drawdown, close / peak - 1);
        }
        return drawdown;
    }

    private static double typical(DailyBar bar) {
        return (bar.high().doubleValue() + bar.low().doubleValue() + bar.close().doubleValue()) / 3;
    }

    private static double number(java.math.BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }

    private static double averageLast(List<Double> values, int count) {
        return CustomIndicators.mean(tail(values, count));
    }

    private static double last(List<Double> values) {
        return values.getLast();
    }

    static <T> List<T> tail(List<T> values, int count) {
        return List.copyOf(values.subList(Math.max(0, values.size() - count), values.size()));
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", finite(value));
    }
}
