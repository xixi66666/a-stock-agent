package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.Quote;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 字段级主备合并：只在主值为空时补齐，并记录每个来源补了哪些字段。 */
public final class QuoteMerger {

    public record Source(String provider, Quote quote) {}

    public record Outcome(Quote quote, Map<String, List<String>> filledByProvider) {}

    private static final Map<String, Function<Quote, Object>> READERS = readers();

    private QuoteMerger() {
    }

    private static Map<String, Function<Quote, Object>> readers() {
        var map = new LinkedHashMap<String, Function<Quote, Object>>();
        map.put("现价", Quote::price);
        map.put("昨收", Quote::previousClose);
        map.put("今开", Quote::open);
        map.put("最高", Quote::high);
        map.put("最低", Quote::low);
        map.put("涨跌额", Quote::changeAmount);
        map.put("涨跌幅", Quote::changePercent);
        map.put("成交量", Quote::volumeShares);
        map.put("成交额", Quote::amountYuan);
        map.put("换手率", Quote::turnoverPercent);
        map.put("振幅", Quote::amplitudePercent);
        map.put("量比", Quote::volumeRatio);
        map.put("市盈率TTM", Quote::peTtm);
        map.put("静态市盈率", Quote::peStatic);
        map.put("市净率", Quote::pb);
        map.put("总市值", Quote::totalMarketValueYuan);
        map.put("流通市值", Quote::circulatingMarketValueYuan);
        map.put("涨停价", Quote::limitUp);
        map.put("跌停价", Quote::limitDown);
        map.put("数据时间", Quote::quotedAt);
        return Collections.unmodifiableMap(map);
    }

    public static Outcome merge(Quote base, List<Source> sources) {
        Quote current = base;
        var filled = new LinkedHashMap<String, List<String>>();
        for (Source source : sources) {
            if (source == null || source.quote() == null) {
                continue;
            }
            Quote before = current;
            current = fill(before, source.quote());
            List<String> fields = changedFields(before, current);
            if (!fields.isEmpty()) {
                filled.put(source.provider(), fields);
            }
        }
        return new Outcome(current, Collections.unmodifiableMap(filled));
    }

    private static List<String> changedFields(Quote before, Quote after) {
        var names = new ArrayList<String>();
        READERS.forEach((label, reader) -> {
            Object oldValue = reader.apply(before);
            Object newValue = reader.apply(after);
            if (blank(oldValue) && !blank(newValue)) {
                names.add(label);
            }
        });
        return List.copyOf(names);
    }

    private static boolean blank(Object value) {
        return value == null || (value instanceof BigDecimal number && number.signum() == 0);
    }

    private static Quote fill(Quote base, Quote candidate) {
        return new Quote(
                base.security(), base.name(),
                pick(base.price(), candidate.price()),
                pick(base.previousClose(), candidate.previousClose()),
                pick(base.open(), candidate.open()),
                pick(base.high(), candidate.high()),
                pick(base.low(), candidate.low()),
                pick(base.changeAmount(), candidate.changeAmount()),
                pick(base.changePercent(), candidate.changePercent()),
                pick(base.volumeShares(), candidate.volumeShares()),
                pick(base.amountYuan(), candidate.amountYuan()),
                pick(base.turnoverPercent(), candidate.turnoverPercent()),
                pick(base.amplitudePercent(), candidate.amplitudePercent()),
                pick(base.volumeRatio(), candidate.volumeRatio()),
                pick(base.peTtm(), candidate.peTtm()),
                pick(base.peStatic(), candidate.peStatic()),
                pick(base.pb(), candidate.pb()),
                pick(base.totalMarketValueYuan(), candidate.totalMarketValueYuan()),
                pick(base.circulatingMarketValueYuan(), candidate.circulatingMarketValueYuan()),
                pick(base.limitUp(), candidate.limitUp()),
                pick(base.limitDown(), candidate.limitDown()),
                pick(base.quotedAt(), candidate.quotedAt()));
    }

    private static <T> T pick(T base, T candidate) {
        if (base != null) {
            return base;
        }
        return candidate;
    }
}
