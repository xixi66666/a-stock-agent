package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单个报告期的规范化财报字段。
 *
 * <p>lrb/llb 字段为年初至今累计口径;fzb 字段为时点值。缺失字段为 null,
 * 下游必须显式处理,不得补造数值。</p>
 */
public record FinancialPeriodStatement(
        LocalDate reportPeriod,
        BigDecimal operatingRevenue,
        BigDecimal operatingCost,
        BigDecimal netProfit,
        BigDecimal netProfitAttributable,
        BigDecimal operatingCashFlow,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal currentAssets,
        BigDecimal currentLiabilities,
        BigDecimal shareCapital,
        BigDecimal equityAttributable) {
}
