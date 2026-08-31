package com.astock.agent.analysis.financial;

import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * 累计口径的 TTM 组法与通用财务计算辅助。
 *
 * <p>lrb/llb 报告期数值是年初至今累计:TTM = 当期累计 + 上年年报 − 上年同期;
 * 当期是年报时 TTM 即全年累计。组不出(期数不足或字段缺失)返回 null。</p>
 */
public final class FinancialTtm {

    private FinancialTtm() {
    }

    public static BigDecimal ttm(List<FinancialPeriodStatement> periods, int index,
            Function<FinancialPeriodStatement, BigDecimal> value) {
        FinancialPeriodStatement current = periods.get(index);
        BigDecimal cumulative = value.apply(current);
        if (cumulative == null) {
            return null;
        }
        if (current.reportPeriod().getMonthValue() == 12) {
            return cumulative;
        }
        FinancialPeriodStatement sameLastYear = periodAt(periods,
                current.reportPeriod().minusYears(1));
        FinancialPeriodStatement priorAnnual = annualOfPreviousYear(periods, current.reportPeriod());
        if (sameLastYear == null || priorAnnual == null
                || value.apply(sameLastYear) == null || value.apply(priorAnnual) == null) {
            return null;
        }
        return cumulative.add(value.apply(priorAnnual)).subtract(value.apply(sameLastYear));
    }

    public static FinancialPeriodStatement periodAt(List<FinancialPeriodStatement> periods,
            LocalDate period) {
        for (FinancialPeriodStatement statement : periods) {
            if (statement.reportPeriod().equals(period)) {
                return statement;
            }
        }
        return null;
    }

    public static FinancialPeriodStatement annualOfPreviousYear(
            List<FinancialPeriodStatement> periods, LocalDate current) {
        FinancialPeriodStatement found = null;
        for (FinancialPeriodStatement statement : periods) {
            LocalDate period = statement.reportPeriod();
            if (period.getYear() == current.getYear() - 1 && period.getMonthValue() == 12) {
                found = statement;
            }
        }
        return found;
    }

    public static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 4, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }
}
