package com.astock.agent.marketdata.provider.cninfo;

import com.astock.agent.marketdata.model.Announcement;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.ProviderHttpClient;
import com.astock.agent.marketdata.provider.ProviderId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CninfoAnnouncementClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private final Map<String, String> orgIds = new ConcurrentHashMap<>();
    private final ProviderHttpClient http;
    private final Clock clock;

    public CninfoAnnouncementClient() {
        this(null, Clock.systemUTC());
    }

    public CninfoAnnouncementClient(ProviderHttpClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    public List<Announcement> parseAnnouncements(String body) {
        try {
            List<Announcement> result = new ArrayList<>();
            for (JsonNode item : MAPPER.readTree(body).path("announcements")) {
                String adjunct = item.path("adjunctUrl").asText();
                String url = adjunct.isBlank()
                        ? "https://www.cninfo.com.cn/new/disclosure/detail?annoId="
                                + item.path("announcementId").asText()
                        : "https://static.cninfo.com.cn/" + adjunct;
                long timestamp = item.path("announcementTime").asLong();
                LocalDate date = timestamp == 0L ? null
                        : Instant.ofEpochMilli(timestamp).atZone(CHINA).toLocalDate();
                result.add(new Announcement(
                        item.path("announcementTitle").asText().replaceAll("<[^>]+>", ""),
                        item.path("announcementTypeName").asText(), date, url));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse CNInfo announcements", exception);
        }
    }

    public DataSection<List<Announcement>> fetchAnnouncements(SecurityId security) {
        if (http == null) {
            throw new IllegalStateException("Live CNInfo client is not configured");
        }
        URI uri = URI.create("https://www.cninfo.com.cn/new/hisAnnouncement/query");
        try {
            String orgId = resolveOrgId(security);
            String form = form(Map.ofEntries(
                    Map.entry("stock", security.code() + "," + orgId),
                    Map.entry("tabName", "fulltext"),
                    Map.entry("pageSize", "30"),
                    Map.entry("pageNum", "1"),
                    Map.entry("column", ""),
                    Map.entry("category", ""),
                    Map.entry("plate", ""),
                    Map.entry("seDate", ""),
                    Map.entry("searchkey", ""),
                    Map.entry("secid", ""),
                    Map.entry("sortName", ""),
                    Map.entry("sortType", ""),
                    Map.entry("isHLtitle", "true")));
            String body = http.post(
                    ProviderId.CNINFO, uri, "application/x-www-form-urlencoded",
                    form.getBytes(StandardCharsets.UTF_8), "https://www.cninfo.com.cn/new/disclosure").utf8Text();
            return DataSection.healthy(
                    parseAnnouncements(body),
                    new Provenance(ProviderId.CNINFO.displayName(), uri, null, clock.instant(), false, null));
        } catch (Exception exception) {
            return DataSection.unavailable("CNInfo announcements failed: " + exception.getMessage());
        }
    }

    private String resolveOrgId(SecurityId security) throws Exception {
        if (orgIds.isEmpty()) {
            URI uri = URI.create("https://www.cninfo.com.cn/new/data/szse_stock.json");
            JsonNode list = MAPPER.readTree(http.get(
                    ProviderId.CNINFO, uri, "https://www.cninfo.com.cn/").utf8Text()).path("stockList");
            for (JsonNode item : list) {
                orgIds.put(item.path("code").asText(), item.path("orgId").asText());
            }
        }
        return orgIds.getOrDefault(security.code(), switch (security.exchange()) {
            case SHANGHAI -> "gssh0" + security.code();
            case SHENZHEN -> "gssz0" + security.code();
            case BEIJING -> "gsbj0" + security.code();
        });
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }
}
