package com.astock.agent.analysis.financial;

import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Piotroski F-Score 的 A 股 TTM 适配版:9 个信号各 1 分。
 *
 * <p>流量类信号用 TTM 组法,时点类信号取最新期与上年同期比较;
 * 输入缺失的信号标 UNVERIFIED,不计入总分与分母;期数少于 4 整体数据不足。</p>
 */
public final class FinancialQualityScorer {

    public static final String RULE_VERSION = "financial-fscore-v2-date-alignment";

    private static int previousYearIndex(List<FinancialPeriodStatement> periods, int index) {
        var date = periods.get(index).reportPeriod().minusYears(1);
        for (int i = 0; i < index; i++) {
            if (periods.get(i).reportPeriod().equals(date)) return i;
        }
        return -1;
    }

    public FinancialQualityScore score(FinancialStatementHistory history, boolean financialIndustry) {
        List<FinancialPeriodStatement> periods = history.periods();
        if (periods.size() < 4) {
            return FinancialQualityScore.insufficient();
        }
        int last = periods.size() - 1;
        List<FinancialQualityScore.SignalResult> signals = new ArrayList<>();
        signals.add(signal(1, "TTM ROA 为正", ttmRoaPositive(periods, last)));
        signals.add(signal(2, "TTM 经营现金流为正", ttmCashFlowPositive(periods, last)));
        signals.add(signal(3, "ROA 改善", deltaTtmRoa(periods, last)));
        signals.add(signal(4, "现金流质量(CFO > 净利润)", cashQuality(periods, last)));
        signals.add(signal(5, "杠杆改善(资产负债率下降)", pointInTimeLower(periods, last,
                FinancialQualityScorer::debtRatio)));
        signals.add(signal(6, "流动性改善(流动比率上升)", pointInTimeHigher(periods, last,
                FinancialQualityScorer::currentRatio)));
        signals.add(signal(7, "无股本稀释", noDilution(periods, last)));
        if (financialIndustry) {
            signals.add(new FinancialQualityScore.SignalResult(8, "毛利率改善",
                    FinancialQualityScore.SignalStatus.UNVERIFIED, "金融行业财报无传统毛利率口径"));
            signals.add(new FinancialQualityScore.SignalResult(9, "资产周转率改善",
                    FinancialQualityScore.SignalStatus.UNVERIFIED, "金融行业财报无传统周转率口径"));
        } else {
            signals.add(signal(8, "毛利率改善", deltaTtmGrossMargin(periods, last)));
            signals.add(signal(9, "资产周转率改善", deltaTtmAssetTurnover(periods, last)));
        }
        int total = (int) signals.stream()
                .filter(item -> item.status() == FinancialQualityScore.SignalStatus.PASS).count();
        int evaluated = (int) signals.stream()
                .filter(item -> item.status() != FinancialQualityScore.SignalStatus.UNVERIFIED).count();
        return new FinancialQualityScore(total, tier(total), evaluated, signals, true);
    }

    private static FinancialQualityScore.SignalResult signal(int number, String name, Boolean pass) {
        return pass == null
                ? new FinancialQualityScore.SignalResult(number, name,
                        FinancialQualityScore.SignalStatus.UNVERIFIED, "所需字段或历史期数不足")
                : new FinancialQualityScore.SignalResult(number, name,
                        pass ? FinancialQualityScore.SignalStatus.PASS
                                : FinancialQualityScore.SignalStatus.FAIL,
                        pass ? "条件成立" : "条件不成立");
    }

    private static Boolean ttmRoaPositive(List<FinancialPeriodStatement> periods, int index) {
        BigDecimal roa = ttmRoa(periods, index);
        return roa == null ? null : roa.signum() > 0;
    }

    private static Boolean ttmCashFlowPositive(List<FinancialPeriodStatement> periods, int index) {
        BigDecimal cashFlow = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::operatingCashFlow);
        return cashFlow == null ? null : cashFlow.signum() > 0;
    }

    private static Boolean deltaTtmRoa(List<FinancialPeriodStatement> periods, int index) {
        if (previousYearIndex(periods, index) < 0) {
            return null;
        }
        BigDecimal current = ttmRoa(periods, index);
        BigDecimal prior = ttmRoa(periods, previousYearIndex(periods, index));
        if (current == null || prior == null) {
            return null;
        }
        return current.compareTo(prior) > 0;
    }

    private static BigDecimal ttmRoa(List<FinancialPeriodStatement> periods, int index) {
        BigDecimal np = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::netProfitAttributable);
        return FinancialTtm.ratio(np, averageAssets(periods, index));
    }

    private static Boolean cashQuality(List<FinancialPeriodStatement> periods, int index) {
        BigDecimal cashFlow = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::operatingCashFlow);
        BigDecimal netProfit = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::netProfit);
        if (cashFlow == null || netProfit == null) {
            return null;
        }
        return cashFlow.compareTo(netProfit) > 0;
    }

    private static Boolean pointInTimeLower(List<FinancialPeriodStatement> periods, int index,
            Function<FinancialPeriodStatement, BigDecimal> ratio) {
        if (previousYearIndex(periods, index) < 0) {
            return null;
        }
        BigDecimal current = ratio.apply(periods.get(index));
        BigDecimal prior = ratio.apply(periods.get(previousYearIndex(periods, index)));
        if (current == null || prior == null) {
            return null;
        }
        return current.compareTo(prior) < 0;
    }

    private static Boolean pointInTimeHigher(List<FinancialPeriodStatement> periods, int index,
            Function<FinancialPeriodStatement, BigDecimal> ratio) {
        if (previousYearIndex(periods, index) < 0) {
            return null;
        }
        BigDecimal current = ratio.apply(periods.get(index));
        BigDecimal prior = ratio.apply(periods.get(previousYearIndex(periods, index)));
        if (current == null || prior == null) {
            return null;
        }
        return current.compareTo(prior) > 0;
    }

    private static Boolean noDilution(List<FinancialPeriodStatement> periods, int index) {
        if (previousYearIndex(periods, index) < 0) {
            return null;
        }
        BigDecimal current = periods.get(index).shareCapital();
        BigDecimal prior = periods.get(previousYearIndex(periods, index)).shareCapital();
        if (current == null || prior == null) {
            return null;
        }
        return current.compareTo(prior) <= 0;
    }

    private static Boolean deltaTtmGrossMargin(List<FinancialPeriodStatement> periods, int index) {
        if (previousYearIndex(periods, index) < 0) {
            return null;
        }
        BigDecimal current = ttmGrossMargin(periods, index);
        BigDecimal prior = ttmGrossMargin(periods, previousYearIndex(periods, index));
        if (current == null || prior == null) {
            return null;
        }
        return current.compareTo(prior) > 0;
    }

    private static Boolean deltaTtmAssetTurnover(List<FinancialPeriodStatement> periods, int index) {
        if (previousYearIndex(periods, index) < 0) {
            return null;
        }
        BigDecimal current = ttmAssetTurnover(periods, index);
        BigDecimal prior = ttmAssetTurnover(periods, previousYearIndex(periods, index));
        if (current == null || prior == null) {
            return null;
        }
        return current.compareTo(prior) > 0;
    }

    private static BigDecimal ttmGrossMargin(List<FinancialPeriodStatement> periods, int index) {
        BigDecimal revenue = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::operatingRevenue);
        BigDecimal cost = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::operatingCost);
        if (revenue == null || cost == null) {
            return null;
        }
        return FinancialTtm.percent(revenue.subtract(cost), revenue);
    }

    private static BigDecimal ttmAssetTurnover(List<FinancialPeriodStatement> periods, int index) {
        BigDecimal revenue = FinancialTtm.ttm(periods, index, FinancialPeriodStatement::operatingRevenue);
        return FinancialTtm.ratio(revenue, averageAssets(periods, index));
    }

    private static BigDecimal averageAssets(List<FinancialPeriodStatement> periods, int index) {
        if (previousYearIndex(periods, index) < 0) {
            return null;
        }
        BigDecimal current = periods.get(index).totalAssets();
        BigDecimal prior = periods.get(previousYearIndex(periods, index)).totalAssets();
        if (current == null || prior == null) {
            return null;
        }
        return current.add(prior).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal debtRatio(FinancialPeriodStatement statement) {
        return FinancialTtm.percent(statement.totalLiabilities(), statement.totalAssets());
    }

    private static BigDecimal currentRatio(FinancialPeriodStatement statement) {
        return FinancialTtm.ratio(statement.currentAssets(), statement.currentLiabilities());
    }

    static String tier(int total) {
        if (total <= 2) {
            return "弱";
        }
        if (total <= 5) {
            return "中";
        }
        if (total <= 7) {
            return "良";
        }
        return "优";
    }
}
