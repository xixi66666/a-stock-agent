package com.astock.agent.analysis.financial;

import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 多期趋势计算:固定 8 个系列,同比为当期 vs 上年同期;
 * 方向取最近 3 个可比同比对的符号一致性,加速度取最近两个同比之差,
 * 波动率取可比同比序列标准差。全部为确定性纯函数。
 */
public final class FinancialTrendCalculator {

    public FinancialTrendResult calculate(FinancialStatementHistory history) {
        List<FinancialPeriodStatement> periods = history.periods();
        List<FinancialTrendResult.TrendSeries> series = List.of(
                build("营业总收入", "元", "CUMULATIVE",
                        points(periods, FinancialPeriodStatement::operatingRevenue)),
                build("归母净利润", "元", "CUMULATIVE",
                        points(periods, FinancialPeriodStatement::netProfitAttributable)),
                build("净利润", "元", "CUMULATIVE",
                        points(periods, FinancialPeriodStatement::netProfit)),
                build("毛利率", "%", "CUMULATIVE",
                        points(periods, statement -> margin(statement.operatingRevenue(), statement.operatingCost()))),
                build("净利率", "%", "CUMULATIVE",
                        points(periods, statement -> FinancialTtm.percent(statement.netProfit(), statement.operatingRevenue()))),
                build("ROE_TTM", "%", "CUMULATIVE", roePoints(periods)),
                build("经营现金流", "元", "CUMULATIVE",
                        points(periods, FinancialPeriodStatement::operatingCashFlow)),
                build("资产负债率", "%", "POINT_IN_TIME",
                        points(periods, statement -> FinancialTtm.percent(statement.totalLiabilities(), statement.totalAssets()))));
        return new FinancialTrendResult(series, periods.size());
    }

    private static List<FinancialTrendResult.TrendPoint> points(List<FinancialPeriodStatement> periods,
            Function<FinancialPeriodStatement, BigDecimal> value) {
        List<FinancialTrendResult.TrendPoint> result = new ArrayList<>();
        for (FinancialPeriodStatement period : periods) {
            result.add(new FinancialTrendResult.TrendPoint(period.reportPeriod(), value.apply(period)));
        }
        return result;
    }

    private static List<FinancialTrendResult.TrendPoint> roePoints(List<FinancialPeriodStatement> periods) {
        List<FinancialTrendResult.TrendPoint> result = new ArrayList<>();
        for (int i = 0; i < periods.size(); i++) {
            BigDecimal np = FinancialTtm.ttm(periods, i, FinancialPeriodStatement::netProfitAttributable);
            result.add(new FinancialTrendResult.TrendPoint(periods.get(i).reportPeriod(),
                    FinancialTtm.percent(np, periods.get(i).equityAttributable())));
        }
        return result;
    }

    private static FinancialTrendResult.TrendSeries build(String name, String unit, String caliber,
            List<FinancialTrendResult.TrendPoint> points) {
        List<BigDecimal> yoy = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            yoy.add(yoyGrowth(points, i));
        }
        return new FinancialTrendResult.TrendSeries(name, unit, caliber,
                java.util.Collections.unmodifiableList(new ArrayList<>(points)),
                java.util.Collections.unmodifiableList(yoy),
                direction(yoy), acceleration(yoy), volatility(yoy));
    }

    private static BigDecimal margin(BigDecimal revenue, BigDecimal cost) {
        if (revenue == null || cost == null) {
            return null;
        }
        return FinancialTtm.percent(revenue.subtract(cost), revenue);
    }

    private static BigDecimal yoyGrowth(List<FinancialTrendResult.TrendPoint> points, int index) {
        if (index < 4) {
            return null;
        }
        BigDecimal current = points.get(index).value();
        BigDecimal prior = points.get(index - 4).value();
        if (current == null || prior == null || prior.signum() == 0) {
            return null;
        }
        return current.subtract(prior)
                .divide(prior, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static String direction(List<BigDecimal> yoy) {
        List<BigDecimal> recent = new ArrayList<>();
        for (int i = yoy.size() - 1; i >= 0 && recent.size() < 3; i--) {
            if (yoy.get(i) != null) {
                recent.add(yoy.get(i));
            }
        }
        if (recent.isEmpty()) {
            return "INSUFFICIENT";
        }
        boolean allPositive = recent.stream().allMatch(value -> value.signum() > 0);
        boolean allNegative = recent.stream().allMatch(value -> value.signum() < 0);
        if (allPositive) {
            return "RISING";
        }
        if (allNegative) {
            return "FALLING";
        }
        return "MIXED";
    }

    private static BigDecimal acceleration(List<BigDecimal> yoy) {
        BigDecimal latest = nthNonNullFromEnd(yoy, 2);
        BigDecimal previous = nthNonNullFromEnd(yoy, 3);
        if (latest == null || previous == null) {
            return null;
        }
        return latest.subtract(previous).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nthNonNullFromEnd(List<BigDecimal> yoy, int n) {
        int count = 0;
        for (int i = yoy.size() - 1; i >= 0; i--) {
            if (yoy.get(i) != null && ++count == n) {
                return yoy.get(i);
            }
        }
        return null;
    }

    private static BigDecimal volatility(List<BigDecimal> yoy) {
        List<BigDecimal> available = yoy.stream().filter(value -> value != null).toList();
        if (available.size() < 2) {
            return null;
        }
        BigDecimal mean = available.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(available.size()), MathContext.DECIMAL64);
        BigDecimal variance = available.stream()
                .map(value -> value.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(available.size()), MathContext.DECIMAL64);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
