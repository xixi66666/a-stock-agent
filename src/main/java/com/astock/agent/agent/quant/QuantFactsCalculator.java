package com.astock.agent.agent.quant;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DailyBar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 基于日线和基准日线的确定性指标计算器。 */
public final class QuantFactsCalculator {
    private static final int TRADING_DAYS = 252;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final List<Integer> RETURN_WINDOWS = List.of(5, 20, 60, 120, 250);

    public QuantReportFacts calculate(StockResearchSnapshot snapshot,
                                      Map<String, List<DailyBar>> benchmarkBars) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<String, MetricObservation> metrics = new LinkedHashMap<>();
        List<DailyBar> bars = validBars(snapshot);
        List<String> sources = snapshot.bars().provenance().map(p -> p.provider() + ":" + p.sourceUrl())
                .map(List::of).orElse(List.of());
        LocalDate asOf = bars.isEmpty() ? null : bars.getLast().date();

        for (int window : RETURN_WINDOWS) {
            String id = "return-" + window;
            metrics.put(id, returnMetric(id, bars, window, asOf, sources));
        }
        metrics.put("annualized-volatility", dispersionMetric("annualized-volatility", bars, asOf, sources, false));
        metrics.put("downside-volatility", dispersionMetric("downside-volatility", bars, asOf, sources, true));
        metrics.put("max-drawdown", maxDrawdown(bars, asOf, sources));
        metrics.put("sharpe", ratioMetric("sharpe", bars, asOf, sources));
        metrics.put("sortino", sortinoMetric(bars, asOf, sources));
        metrics.put("calmar", calmarMetric(bars, asOf, sources));
        metrics.put("var-95", percentileMetric("var-95", bars, asOf, sources, false));
        metrics.put("cvar-95", percentileMetric("cvar-95", bars, asOf, sources, true));
        metrics.put("skewness", momentMetric("skewness", bars, asOf, sources, 3));
        metrics.put("kurtosis", momentMetric("kurtosis", bars, asOf, sources, 4));

        Map<String, BenchmarkComparison> comparisons = new LinkedHashMap<>();
        if (benchmarkBars != null) {
            benchmarkBars.forEach((id, values) -> comparisons.put(id,
                    compare(id, bars, values, asOf, sources)));
        }
        List<String> limitations = new ArrayList<>();
        if (bars.isEmpty()) {
            limitations.add("个股日线不可用或未通过校验");
        }
        return new QuantReportFacts(metrics, comparisons, List.of(), sources, limitations);
    }

    private static List<DailyBar> validBars(StockResearchSnapshot snapshot) {
        if (snapshot.bars() == null || snapshot.bars().payload().isEmpty()) return List.of();
        List<DailyBar> bars = snapshot.bars().payload().orElseThrow().stream()
                .filter(Objects::nonNull)
                .filter(b -> b.date() != null && b.close() != null && b.close().signum() > 0)
                .sorted(Comparator.comparing(DailyBar::date))
                .collect(Collectors.toCollection(ArrayList::new));
        for (int i = 1; i < bars.size(); i++) {
            if (bars.get(i).date().equals(bars.get(i - 1).date())) return List.of();
        }
        return bars;
    }

    private static MetricObservation returnMetric(String id, List<DailyBar> bars, int window,
                                                  LocalDate asOf, List<String> sources) {
        if (bars.size() <= window) return insufficient(id, "%", window + "日", asOf, sources, "至少需要窗口+1根有效日线");
        BigDecimal start = bars.get(bars.size() - 1 - window).close();
        BigDecimal end = bars.getLast().close();
        return available(id, end.divide(start, 12, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                        .multiply(HUNDRED), "%", window + "日", asOf,
                "区间收益=(期末收盘/期初收盘-1)*100", sources);
    }

    private static MetricObservation dispersionMetric(String id, List<DailyBar> bars, LocalDate asOf,
                                                      List<String> sources, boolean downside) {
        List<Double> returns = returns(bars);
        if (returns.size() < 20) return insufficient(id, "%", "历史日收益", asOf, sources, "至少需要20个有效日收益");
        List<Double> selected = downside ? returns.stream().filter(v -> v < 0).toList() : returns;
        if (selected.size() < 2) return insufficient(id, "%", "历史日收益", asOf, sources, "下行收益样本不足");
        double sd = sampleStd(selected);
        return available(id, BigDecimal.valueOf(sd * Math.sqrt(TRADING_DAYS) * 100), "%", "历史日收益",
                asOf, (downside ? "下行" : "全样本") + "收益样本标准差*sqrt(252)*100", sources);
    }

    private static MetricObservation maxDrawdown(List<DailyBar> bars, LocalDate asOf, List<String> sources) {
        if (bars.size() < 2) return insufficient("max-drawdown", "%", "全历史", asOf, sources, "至少需要2根有效日线");
        double peak = bars.getFirst().close().doubleValue();
        double drawdown = 0;
        for (DailyBar bar : bars) {
            double close = bar.close().doubleValue();
            peak = Math.max(peak, close);
            drawdown = Math.min(drawdown, close / peak - 1);
        }
        return available("max-drawdown", BigDecimal.valueOf(drawdown * 100), "%", "全历史", asOf,
                "逐日收盘净值相对历史峰值的最小跌幅*100", sources);
    }

    private static MetricObservation ratioMetric(String id, List<DailyBar> bars, LocalDate asOf, List<String> sources) {
        List<Double> returns = returns(bars);
        if (returns.size() < 20) return insufficient(id, "", "历史日收益", asOf, sources, "至少需要20个有效日收益");
        double sd = sampleStd(returns);
        if (sd == 0) return unavailable(id, "", "历史日收益", asOf, sources, "收益波动为零，Sharpe不可定义");
        return available(id, BigDecimal.valueOf(average(returns) / sd * Math.sqrt(TRADING_DAYS)), "", "历史日收益", asOf,
                "无风险利率按0处理；均值/样本标准差*sqrt(252)", sources);
    }

    private static MetricObservation sortinoMetric(List<DailyBar> bars, LocalDate asOf, List<String> sources) {
        List<Double> returns = returns(bars);
        List<Double> downside = returns.stream().filter(v -> v < 0).toList();
        if (returns.size() < 20 || downside.size() < 2) return insufficient("sortino", "", "历史日收益", asOf, sources, "日收益或下行收益样本不足");
        return available("sortino", BigDecimal.valueOf(average(returns) / sampleStd(downside) * Math.sqrt(TRADING_DAYS)), "", "历史日收益", asOf,
                "无风险利率按0；均值/下行样本标准差*sqrt(252)", sources);
    }

    private static MetricObservation calmarMetric(List<DailyBar> bars, LocalDate asOf, List<String> sources) {
        MetricObservation ret = returnMetric("return-250", bars, 250, asOf, sources);
        MetricObservation dd = maxDrawdown(bars, asOf, sources);
        if (ret.availability() != MetricAvailability.AVAILABLE || dd.availability() != MetricAvailability.AVAILABLE
                || dd.value().signum() == 0) return insufficient("calmar", "", "250日/全历史", asOf, sources, "Calmar所需年化收益或回撤不可用");
        return available("calmar", ret.value().divide(dd.value().abs(), 12, RoundingMode.HALF_UP), "", "250日/全历史", asOf,
                "250日收益率/最大回撤绝对值", sources);
    }

    private static MetricObservation percentileMetric(String id, List<DailyBar> bars, LocalDate asOf,
                                                      List<String> sources, boolean tailMean) {
        List<Double> returns = returns(bars);
        if (returns.size() < 20) return insufficient(id, "%", "历史日收益", asOf, sources, "至少需要20个有效日收益");
        List<Double> sorted = returns.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.05) - 1);
        double threshold = sorted.get(index);
        double value = tailMean ? sorted.stream().limit(index + 1).mapToDouble(Double::doubleValue).average().orElse(threshold) : threshold;
        return available(id, BigDecimal.valueOf(value * 100), "%", "历史日收益", asOf,
                (tailMean ? "5%左尾收益均值" : "5%分位数收益") + "*100", sources);
    }

    private static MetricObservation momentMetric(String id, List<DailyBar> bars, LocalDate asOf,
                                                  List<String> sources, int order) {
        List<Double> returns = returns(bars);
        if (returns.size() < 20) return insufficient(id, "", "历史日收益", asOf, sources, "至少需要20个有效日收益");
        double mean = average(returns), sd = sampleStd(returns);
        if (sd == 0) return unavailable(id, "", "历史日收益", asOf, sources, "收益波动为零，矩不可定义");
        double sum = returns.stream().mapToDouble(v -> Math.pow((v - mean) / sd, order)).sum();
        double value = sum / returns.size() - (order == 4 ? 3 : 0);
        return available(id, BigDecimal.valueOf(value), "", "历史日收益", asOf,
                order == 3 ? "标准化三阶中心矩" : "标准化四阶中心矩-3（超额峰度）", sources);
    }

    private static BenchmarkComparison compare(String id, List<DailyBar> stock, List<DailyBar> benchmark,
                                               LocalDate asOf, List<String> sources) {
        if (benchmark == null || benchmark.isEmpty()) {
            return new BenchmarkComparison(id, null, null, null, asOf,
                    MetricAvailability.UNAVAILABLE, "基准日线不可用，未执行比较计算", sources,
                    List.of("基准日线不可用"));
        }
        List<DailyBar> valid = benchmark == null ? List.of() : benchmark.stream().filter(Objects::nonNull)
                .filter(b -> b.date() != null && b.close() != null && b.close().signum() > 0)
                .sorted(Comparator.comparing(DailyBar::date)).toList();
        Map<LocalDate, Double> byDate = valid.stream().collect(Collectors.toMap(DailyBar::date,
                b -> b.close().doubleValue(), (a, b) -> a));
        List<Double> sr = new ArrayList<>(), br = new ArrayList<>();
        for (int i = 1; i < stock.size(); i++) {
            DailyBar previous = stock.get(i - 1), current = stock.get(i);
            Double previousBenchmark = byDate.get(previous.date()), currentBenchmark = byDate.get(current.date());
            if (previousBenchmark != null && currentBenchmark != null) {
                sr.add(current.close().doubleValue() / previous.close().doubleValue() - 1);
                br.add(currentBenchmark / previousBenchmark - 1);
            }
        }
        if (sr.size() < 20) return new BenchmarkComparison(id, null, null, null, asOf,
                MetricAvailability.INSUFFICIENT_SAMPLE, "按相同交易日对齐；至少需要20个配对收益", sources,
                List.of("个股与基准有效交易日配对不足"));
        double meanS = average(sr), meanB = average(br), varianceB = covariance(br, br);
        double beta = varianceB == 0 ? Double.NaN : covariance(sr, br) / varianceB;
        List<Double> active = new ArrayList<>();
        for (int i = 0; i < sr.size(); i++) active.add(sr.get(i) - br.get(i));
        double ir = sampleStd(active) == 0 ? Double.NaN : average(active) / sampleStd(active) * Math.sqrt(TRADING_DAYS);
        double stockCumulative = cumulativeReturn(sr);
        double benchmarkCumulative = cumulativeReturn(br);
        return new BenchmarkComparison(id, BigDecimal.valueOf((stockCumulative - benchmarkCumulative) * 100),
                finite(beta) ? BigDecimal.valueOf(beta) : null, finite(ir) ? BigDecimal.valueOf(ir) : null,
                asOf, MetricAvailability.AVAILABLE, "相同日期配对收益；超额收益=个股累计收益-基准累计收益；Beta=Cov(个股,基准)/Var(基准)，IR=主动收益均值/主动收益标准差*sqrt(252)",
                sources, List.of());
    }

    private static List<Double> returns(List<DailyBar> bars) {
        List<Double> values = new ArrayList<>();
        for (int i = 1; i < bars.size(); i++) values.add(bars.get(i).close().doubleValue() / bars.get(i - 1).close().doubleValue() - 1);
        return values;
    }

    private static double average(List<Double> values) { return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN); }
    private static double cumulativeReturn(List<Double> values) {
        double result = 1;
        for (double value : values) result *= 1 + value;
        return result - 1;
    }
    private static double sampleStd(List<Double> values) {
        double mean = average(values);
        return Math.sqrt(values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / (values.size() - 1));
    }
    private static double covariance(List<Double> a, List<Double> b) {
        double am = average(a), bm = average(b);
        double sum = 0; for (int i = 0; i < a.size(); i++) sum += (a.get(i) - am) * (b.get(i) - bm);
        return sum / (a.size() - 1);
    }
    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private static MetricObservation available(String id, BigDecimal value, String unit, String window, LocalDate asOf,
                                               String method, List<String> sources) {
        return new MetricObservation(id, value.setScale(Math.min(8, Math.max(1, value.scale())), RoundingMode.HALF_UP), unit, window, asOf,
                MetricAvailability.AVAILABLE, method, sources, List.of());
    }
    private static MetricObservation insufficient(String id, String unit, String window, LocalDate asOf,
                                                 List<String> sources, String limitation) {
        return MetricObservation.unavailable(id, unit, window, asOf, MetricAvailability.INSUFFICIENT_SAMPLE,
                "确定性计算规则", sources, limitation);
    }
    private static MetricObservation unavailable(String id, String unit, String window, LocalDate asOf,
                                                List<String> sources, String limitation) {
        return MetricObservation.unavailable(id, unit, window, asOf, MetricAvailability.UNAVAILABLE,
                "确定性计算规则", sources, limitation);
    }
}
