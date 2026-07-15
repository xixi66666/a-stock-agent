package com.astock.agent.marketdata.provider.tencent;

import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TencentResponseParser {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
    private static final BigDecimal HUNDRED_MILLION = new BigDecimal("100000000");
    private static final DateTimeFormatter QUOTE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Quote parseQuote(String body, SecurityId requested) {
        int firstQuote = body.indexOf('"');
        int lastQuote = body.lastIndexOf('"');
        if (firstQuote < 0 || lastQuote <= firstQuote) {
            throw new IllegalArgumentException("Tencent quote response is malformed");
        }
        String[] values = body.substring(firstQuote + 1, lastQuote).split("~", -1);
        if (values.length < 53 || !requested.code().equals(values[2])) {
            throw new IllegalArgumentException("Tencent quote identity or field count mismatch");
        }

        return new Quote(
                requested,
                values[1],
                decimal(values[3]),
                decimal(values[4]),
                decimal(values[5]),
                decimal(values[33]),
                decimal(values[34]),
                decimal(values[31]),
                decimal(values[32]),
                multiply(values[36], HUNDRED),
                multiply(values[37], TEN_THOUSAND),
                decimal(values[38]),
                decimal(values[43]),
                decimal(values[49]),
                decimal(values[39]),
                decimal(values[52]),
                decimal(values[46]),
                multiply(values[44], HUNDRED_MILLION),
                multiply(values[45], HUNDRED_MILLION),
                decimal(values[47]),
                decimal(values[48]),
                parseInstant(values[30]));
    }

    public List<DailyBar> parseDailyBars(String body, SecurityId requested) {
        try {
            JsonNode security = objectMapper.readTree(body).path("data").path(requested.tencentCode());
            JsonNode rows = security.has("qfqday") ? security.path("qfqday") : security.path("day");
            if (!rows.isArray()) {
                throw new IllegalArgumentException("Tencent K-line payload is missing qfqday/day");
            }
            Map<LocalDate, DailyBar> byDate = new LinkedHashMap<>();
            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() < 6) {
                    continue;
                }
                DailyBar bar = new DailyBar(
                        LocalDate.parse(row.get(0).asText()),
                        decimal(row.get(1).asText()),
                        decimal(row.get(3).asText()),
                        decimal(row.get(4).asText()),
                        decimal(row.get(2).asText()),
                        multiply(row.get(5).asText(), HUNDRED),
                        null);
                DailyBar previous = byDate.putIfAbsent(bar.date(), bar);
                if (previous != null && !previous.equals(bar)) {
                    throw new IllegalArgumentException("Conflicting Tencent K-line date: " + bar.date());
                }
            }
            List<DailyBar> result = new ArrayList<>(byDate.values());
            result.sort(Comparator.comparing(DailyBar::date));
            return List.copyOf(result);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse Tencent K-line payload", exception);
        }
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank() || "--".equals(value) || "-".equals(value)) {
            return null;
        }
        return new BigDecimal(value);
    }

    private static BigDecimal multiply(String value, BigDecimal factor) {
        BigDecimal number = decimal(value);
        return number == null ? null : number.multiply(factor).setScale(0, RoundingMode.HALF_UP);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value, QUOTE_TIME).atZone(CHINA).toInstant();
    }
}
