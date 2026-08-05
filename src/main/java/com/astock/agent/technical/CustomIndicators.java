package com.astock.agent.technical;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ta4j 未覆盖的少量技术统计公式。
 *
 * <p>这些方法保持纯函数特征，输入相同就得到相同结果，便于离线测试和审计；
 * 它们不负责数据抓取，也不把统计结果包装成投资建议。</p>
 */
public final class CustomIndicators {

    private CustomIndicators() {
    }

    public static double psy(List<Double> closes, int period) {
        int comparisons = Math.min(period, closes.size() - 1);
        if (comparisons <= 0) {
            return Double.NaN;
        }
        int up = 0;
        for (int i = closes.size() - comparisons; i < closes.size(); i++) {
            if (closes.get(i) > closes.get(i - 1)) {
                up++;
            }
        }
        return up * 100.0 / comparisons;
    }

    public static double historicalVar(List<Double> returns, double confidence) {
        List<Double> sorted = sorted(returns);
        int index = Math.max(0, (int) Math.ceil(sorted.size() * (1.0 - confidence)) - 1);
        return sorted.get(index);
    }

    public static double historicalCvar(List<Double> returns, double confidence) {
        List<Double> sorted = sorted(returns);
        int count = Math.max(1, (int) Math.ceil(sorted.size() * (1.0 - confidence)));
        return sorted.subList(0, count).stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    public static double skewness(List<Double> values) {
        double mean = mean(values);
        double sd = standardDeviation(values);
        return sd == 0 ? 0 : values.stream().mapToDouble(v -> Math.pow((v - mean) / sd, 3)).average().orElse(0);
    }

    public static double kurtosis(List<Double> values) {
        double mean = mean(values);
        double sd = standardDeviation(values);
        return sd == 0 ? 0 : values.stream().mapToDouble(v -> Math.pow((v - mean) / sd, 4)).average().orElse(0) - 3;
    }

    public static double standardDeviation(List<Double> values) {
        double mean = mean(values);
        return Math.sqrt(values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0));
    }

    public static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static List<Double> sorted(List<Double> values) {
        if (values.isEmpty()) {
            return List.of(Double.NaN);
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }
}
