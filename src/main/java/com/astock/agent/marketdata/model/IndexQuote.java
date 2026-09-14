package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 指数快照的规范化记录。
 *
 * <p>指数不复用 {@link SecurityId}：没有交易所六位证券代码语义。涨跌额和涨跌幅允许为空，
 * 源字段缺失时保持空值，不推导、不补零；点位与前收必须为正数。</p>
 */
public record IndexQuote(
        BenchmarkId benchmark,
        String displayName,
        BigDecimal lastPoint,
        BigDecimal previousClose,
        BigDecimal changeAmount,
        BigDecimal changePercent,
        Instant quotedAt) {
}
