package com.astock.agent.marketdata.provider.baidu;

import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.ProviderHttpClient;
import com.astock.agent.marketdata.provider.ProviderId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BaiduKlineClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ProviderHttpClient http;
    private final Clock clock;

    public BaiduKlineClient(ProviderHttpClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    public SourcedBars fetchDailyBars(SecurityId security) {
        String query = "all=1&isIndex=false&isBk=false&isBlock=false&isFutures=false&isStock=true"
                + "&newFormat=1&group=quotation_kline_ab&finClientType=pc&ktype=1&code="
                + URLEncoder.encode(security.code(), StandardCharsets.UTF_8);
        URI uri = URI.create("https://finance.pae.baidu.com/selfselect/getstockquotation?" + query);
        String body = http.get(ProviderId.BAIDU, uri, "https://gushitong.baidu.com/").utf8Text();
        List<DailyBar> bars = parseDailyBars(body, security);
        Provenance provenance = new Provenance(
                ProviderId.BAIDU.displayName(), uri, null, clock.instant(), false, null);
        return new SourcedBars(bars, provenance);
    }

    public static List<DailyBar> parseDailyBars(String body, SecurityId requested) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!"0".equals(root.path("ResultCode").asText())) {
                throw new IllegalArgumentException("Baidu K-line ResultCode is not zero");
            }
            JsonNode market = root.path("Result").path("newMarketData");
            Map<String, Integer> indexes = new HashMap<>();
            JsonNode keys = market.path("keys");
            for (int i = 0; i < keys.size(); i++) {
                indexes.put(keys.get(i).asText(), i);
            }
            requireKeys(indexes, "time", "open", "close", "volume", "high", "low", "amount");
            Map<LocalDate, DailyBar> byDate = new LinkedHashMap<>();
            for (String encoded : market.path("marketData").asText().split(";")) {
                if (encoded.isBlank()) {
                    continue;
                }
                String[] values = encoded.split(",", -1);
                DailyBar bar = new DailyBar(
                        LocalDate.parse(value(values, indexes, "time")),
                        decimal(value(values, indexes, "open")),
                        decimal(value(values, indexes, "high")),
                        decimal(value(values, indexes, "low")),
                        decimal(value(values, indexes, "close")),
                        decimal(value(values, indexes, "volume")),
                        decimal(value(values, indexes, "amount")));
                DailyBar previous = byDate.putIfAbsent(bar.date(), bar);
                if (previous != null && !previous.equals(bar)) {
                    throw new IllegalArgumentException("Conflicting Baidu K-line date: " + bar.date());
                }
            }
            List<DailyBar> result = new ArrayList<>(byDate.values());
            result.sort(Comparator.comparing(DailyBar::date));
            return List.copyOf(result);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse Baidu K-line payload for " + requested.code(), exception);
        }
    }

    private static void requireKeys(Map<String, Integer> indexes, String... names) {
        for (String name : names) {
            if (!indexes.containsKey(name)) {
                throw new IllegalArgumentException("Baidu K-line missing key: " + name);
            }
        }
    }

    private static String value(String[] values, Map<String, Integer> indexes, String key) {
        int index = indexes.get(key);
        if (index >= values.length) {
            throw new IllegalArgumentException("Baidu K-line row is shorter than keys");
        }
        return values[index];
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank() || "--".equals(value)) {
            return null;
        }
        return new BigDecimal(value.replace("+", ""));
    }

    public record SourcedBars(List<DailyBar> bars, Provenance provenance) {
    }
}
