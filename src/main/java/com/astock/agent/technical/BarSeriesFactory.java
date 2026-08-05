package com.astock.agent.technical;

import com.astock.agent.marketdata.model.DailyBar;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

/**
 * 把规范化日 K 线转换为 ta4j 序列或更高周期 K 线。
 *
 * <p>周期聚合遵循开盘取首条、收盘取末条、最高最低取极值、成交量求和的 OHLCV 规则，
 * 这样技术指标的输入仍然具有明确的金融语义。</p>
 */
public final class BarSeriesFactory {

    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    public BarSeries toSeries(List<DailyBar> bars, String name) {
        BarSeries series = new BaseBarSeriesBuilder().withName(name).build();
        for (DailyBar bar : bars.stream().sorted(Comparator.comparing(DailyBar::date)).toList()) {
            series.barBuilder()
                    .timePeriod(Duration.ofDays(1))
                    .endTime(bar.date().plusDays(1).atStartOfDay(CHINA).toInstant())
                    .openPrice(bar.open().toPlainString())
                    .highPrice(bar.high().toPlainString())
                    .lowPrice(bar.low().toPlainString())
                    .closePrice(bar.close().toPlainString())
                    .volume(value(bar.volumeShares()).toPlainString())
                    .amount(value(bar.amountYuan()).toPlainString())
                    .add();
        }
        return series;
    }

    public List<DailyBar> aggregate(List<DailyBar> dailyBars, Timeframe timeframe) {
        // 聚合前假设上游已经完成排序和重复日期校验；这里不静默修正脏数据。
        List<DailyBar> sorted = dailyBars.stream().sorted(Comparator.comparing(DailyBar::date)).toList();
        if (timeframe == Timeframe.DAILY) {
            return List.copyOf(sorted);
        }
        List<DailyBar> result = new ArrayList<>();
        List<DailyBar> bucket = new ArrayList<>();
        String currentKey = null;
        for (DailyBar bar : sorted) {
            String key = periodKey(bar, timeframe);
            if (currentKey != null && !currentKey.equals(key)) {
                result.add(merge(bucket));
                bucket.clear();
            }
            bucket.add(bar);
            currentKey = key;
        }
        if (!bucket.isEmpty()) {
            result.add(merge(bucket));
        }
        return List.copyOf(result);
    }

    private static String periodKey(DailyBar bar, Timeframe timeframe) {
        if (timeframe == Timeframe.MONTHLY) {
            return YearMonth.from(bar.date()).toString();
        }
        WeekFields fields = WeekFields.of(Locale.CHINA);
        return bar.date().get(fields.weekBasedYear()) + "-" + bar.date().get(fields.weekOfWeekBasedYear());
    }

    private static DailyBar merge(List<DailyBar> bars) {
        DailyBar first = bars.getFirst();
        DailyBar last = bars.getLast();
        BigDecimal high = bars.stream().map(DailyBar::high).max(BigDecimal::compareTo).orElse(first.high());
        BigDecimal low = bars.stream().map(DailyBar::low).min(BigDecimal::compareTo).orElse(first.low());
        BigDecimal volume = bars.stream().map(DailyBar::volumeShares).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = bars.stream().map(DailyBar::amountYuan).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new DailyBar(last.date(), first.open(), high, low, last.close(), volume, amount);
    }

    private static BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
