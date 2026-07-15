package com.astock.agent.marketdata.provider.eastmoney;

import com.astock.agent.marketdata.model.CapitalData;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.FundFlow;
import com.astock.agent.marketdata.model.NewsItem;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.ResearchItem;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.model.Sector;
import com.astock.agent.marketdata.provider.ProviderHttpClient;
import com.astock.agent.marketdata.provider.ProviderId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class EastmoneyResearchClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ProviderHttpClient http;
    private final Clock clock;

    public EastmoneyResearchClient() {
        this(null, Clock.systemUTC());
    }

    public EastmoneyResearchClient(ProviderHttpClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    public List<Sector> parseSectors(String body) {
        try {
            JsonNode items = MAPPER.readTree(body).path("data").path("diff");
            List<Sector> result = new ArrayList<>();
            iterable(items).forEach(item -> result.add(new Sector(
                    text(item, "f14"), text(item, "f12"), decimal(item, "f3"), text(item, "f128"))));
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse Eastmoney sectors", exception);
        }
    }

    public List<FundFlow> parseFundFlow(String body) {
        try {
            List<FundFlow> result = new ArrayList<>();
            for (JsonNode encoded : MAPPER.readTree(body).path("data").path("klines")) {
                String[] fields = encoded.asText().split(",", -1);
                if (fields.length >= 6) {
                    result.add(new FundFlow(
                            LocalDate.parse(fields[0]), number(fields[1]), number(fields[2]),
                            number(fields[3]), number(fields[4]), number(fields[5]),
                            ProviderId.EASTMONEY.displayName()));
                }
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse Eastmoney fund flow", exception);
        }
    }

    public List<ResearchItem> parseReports(String body) {
        try {
            List<ResearchItem> result = new ArrayList<>();
            for (JsonNode item : MAPPER.readTree(body).path("data")) {
                String infoCode = text(item, "infoCode");
                result.add(new ResearchItem(
                        text(item, "title"), text(item, "orgSName"), text(item, "emRatingName"),
                        date(text(item, "publishDate")),
                        "https://pdf.dfcfw.com/pdf/H3_" + infoCode + "_1.pdf",
                        decimal(item, "predictThisYearEps"), decimal(item, "predictNextYearEps")));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse Eastmoney reports", exception);
        }
    }

    public List<NewsItem> parseNews(String body) {
        try {
            String json = unwrapJsonp(body);
            JsonNode articles = MAPPER.readTree(json).path("result").path("cmsArticleWebOld");
            List<NewsItem> result = new ArrayList<>();
            for (JsonNode item : articles) {
                result.add(new NewsItem(
                        stripHtml(text(item, "title")), stripHtml(text(item, "content")),
                        text(item, "mediaName"), dateTime(text(item, "date")), text(item, "url")));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse Eastmoney news", exception);
        }
    }

    public CapitalData parseCapitalData(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            List<CapitalData.MarginRecord> margin = new ArrayList<>();
            for (JsonNode item : root.path("margin")) {
                margin.add(new CapitalData.MarginRecord(
                        date(text(item, "DATE")), decimal(item, "RZYE"), decimal(item, "RZMRE"),
                        decimal(item, "RQYE"), decimal(item, "RZRQYE")));
            }
            List<CapitalData.BlockTrade> blocks = new ArrayList<>();
            for (JsonNode item : root.path("blockTrades")) {
                BigDecimal price = decimal(item, "DEAL_PRICE");
                BigDecimal close = decimal(item, "CLOSE_PRICE");
                BigDecimal premium = close == null || close.signum() == 0 || price == null
                        ? null
                        : price.divide(close, MathContext.DECIMAL64).subtract(BigDecimal.ONE)
                                .multiply(new BigDecimal("100"));
                blocks.add(new CapitalData.BlockTrade(
                        date(text(item, "TRADE_DATE")), price, close, premium,
                        decimal(item, "DEAL_VOLUME"), decimal(item, "DEAL_AMT"),
                        text(item, "BUYER_NAME"), text(item, "SELLER_NAME")));
            }
            List<CapitalData.ShareholderChange> holders = new ArrayList<>();
            for (JsonNode item : root.path("holders")) {
                holders.add(new CapitalData.ShareholderChange(
                        date(text(item, "END_DATE")), decimal(item, "HOLDER_NUM"),
                        decimal(item, "HOLDER_NUM_RATIO"), decimal(item, "AVG_FREE_SHARES")));
            }
            List<CapitalData.UnlockRecord> unlocks = new ArrayList<>();
            for (JsonNode item : root.path("unlocks")) {
                unlocks.add(new CapitalData.UnlockRecord(
                        date(text(item, "FREE_DATE")), text(item, "FREE_SHARES_TYPE"),
                        decimal(item, "FREE_SHARES"), decimal(item, "ABLE_FREE_SHARES"),
                        decimal(item, "FREE_RATIO")));
            }
            List<CapitalData.DividendRecord> dividends = new ArrayList<>();
            for (JsonNode item : root.path("dividends")) {
                dividends.add(new CapitalData.DividendRecord(
                        date(text(item, "EX_DIVIDEND_DATE")), decimal(item, "PRETAX_BONUS_RMB"),
                        decimal(item, "TRANSFER_RATIO"), decimal(item, "BONUS_RATIO"),
                        text(item, "ASSIGN_PROGRESS")));
            }
            List<CapitalData.DragonTigerRecord> dragonTiger = new ArrayList<>();
            for (JsonNode item : root.path("dragonTiger")) {
                dragonTiger.add(new CapitalData.DragonTigerRecord(
                        date(text(item, "TRADE_DATE")), text(item, "EXPLANATION"),
                        decimal(item, "BILLBOARD_NET_AMT"), decimal(item, "TURNOVERRATE")));
            }
            return new CapitalData(margin, blocks, holders, unlocks, dividends, dragonTiger);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse Eastmoney capital data", exception);
        }
    }

    public DataSection<List<Sector>> parseSectorsSection(String body, SecurityId security) {
        URI uri = URI.create("https://quote.eastmoney.com/" + security.code() + ".html");
        try {
            List<Sector> sectors = parseSectors(body);
            return DataSection.healthy(sectors, provenance(uri));
        } catch (Exception exception) {
            return DataSection.unavailable("Eastmoney sector parse failed: " + exception.getMessage());
        }
    }

    public DataSection<List<Sector>> fetchSectors(SecurityId security) {
        ensureLiveClient();
        URI uri = URI.create("https://push2.eastmoney.com/api/qt/slist/get?fltt=2&invt=2&spt=3&pi=0&pz=200&po=1"
                + "&fields=f12,f14,f3,f128&secid=" + security.eastmoneySecId());
        return parseSectorsSection(http.get(ProviderId.EASTMONEY, uri, "https://quote.eastmoney.com/").utf8Text(), security);
    }

    public DataSection<List<FundFlow>> fetchFundFlow(SecurityId security) {
        ensureLiveClient();
        URI uri = URI.create("https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get?lmt=120"
                + "&fields1=f1,f2,f3,f7&fields2=f51,f52,f53,f54,f55,f56,f57&secid="
                + security.eastmoneySecId());
        try {
            List<FundFlow> flow = parseFundFlow(http.get(
                    ProviderId.EASTMONEY, uri, "https://quote.eastmoney.com/").utf8Text());
            return DataSection.healthy(flow, provenance(uri));
        } catch (Exception exception) {
            return DataSection.unavailable("Eastmoney fund flow failed: " + exception.getMessage());
        }
    }

    public DataSection<List<ResearchItem>> fetchReports(SecurityId security) {
        ensureLiveClient();
        String query = "industryCode=*&pageSize=20&industry=*&rating=*&ratingChange=*"
                + "&beginTime=2024-01-01&endTime=2030-01-01&pageNo=1&qType=0&code="
                + URLEncoder.encode(security.code(), StandardCharsets.UTF_8);
        URI uri = URI.create("https://reportapi.eastmoney.com/report/list?" + query);
        try {
            List<ResearchItem> items = parseReports(http.get(
                    ProviderId.EASTMONEY, uri, "https://data.eastmoney.com/").utf8Text());
            return DataSection.healthy(items, provenance(uri));
        } catch (Exception exception) {
            return DataSection.unavailable("Eastmoney reports failed: " + exception.getMessage());
        }
    }

    public DataSection<List<NewsItem>> fetchNews(SecurityId security) {
        ensureLiveClient();
        String parameter = "{\"uid\":\"\",\"keyword\":\"" + security.code()
                + "\",\"type\":[\"cmsArticleWebOld\"],\"client\":\"web\","
                + "\"clientType\":\"web\",\"clientVersion\":\"curr\","
                + "\"param\":{\"cmsArticleWebOld\":{\"searchScope\":\"default\","
                + "\"sort\":\"default\",\"pageIndex\":1,\"pageSize\":20,"
                + "\"preTag\":\"\",\"postTag\":\"\"}}}";
        URI uri = URI.create("https://search-api-web.eastmoney.com/search/jsonp?cb=jQuery_news&param="
                + URLEncoder.encode(parameter, StandardCharsets.UTF_8));
        try {
            List<NewsItem> items = parseNews(http.get(
                    ProviderId.EASTMONEY, uri, "https://so.eastmoney.com/").utf8Text());
            return DataSection.healthy(items, provenance(uri));
        } catch (Exception exception) {
            return DataSection.unavailable("Eastmoney news failed: " + exception.getMessage());
        }
    }

    public DataSection<CapitalData> fetchCapitalData(SecurityId security) {
        ensureLiveClient();
        ObjectNode combined = MAPPER.createObjectNode();
        try {
            combined.set("margin", dataCenterRows("RPTA_WEB_RZRQ_GGMX", "(SCODE=\""
                    + security.code() + "\")", "DATE", 30));
            combined.set("blockTrades", dataCenterRows("RPT_DATA_BLOCKTRADE", "(SECURITY_CODE=\""
                    + security.code() + "\")", "TRADE_DATE", 20));
            combined.set("holders", dataCenterRows("RPT_HOLDERNUMLATEST", "(SECURITY_CODE=\""
                    + security.code() + "\")", "END_DATE", 10));
            combined.set("unlocks", dataCenterRows("RPT_LIFT_STAGE", "(SECURITY_CODE=\""
                    + security.code() + "\")", "FREE_DATE", 20));
            combined.set("dividends", dataCenterRows("RPT_SHAREBONUS_DET", "(SECURITY_CODE=\""
                    + security.code() + "\")", "EX_DIVIDEND_DATE", 20));
            combined.set("dragonTiger", dataCenterRows("RPT_DAILYBILLBOARD_DETAILSNEW", "(SECURITY_CODE=\""
                    + security.code() + "\")", "TRADE_DATE", 30));
            URI source = EastmoneyDataCenterQuery.create(
                    "RPTA_WEB_RZRQ_GGMX", "(SCODE=\"" + security.code() + "\")", "DATE", 30);
            return DataSection.healthy(parseCapitalData(MAPPER.writeValueAsString(combined)), provenance(source));
        } catch (Exception exception) {
            return DataSection.unavailable("Eastmoney capital data failed: " + exception.getMessage());
        }
    }

    private ArrayNode dataCenterRows(String reportName, String filter, String sort, int size) throws Exception {
        URI uri = EastmoneyDataCenterQuery.create(reportName, filter, sort, size);
        JsonNode result = MAPPER.readTree(http.get(
                ProviderId.EASTMONEY, uri, "https://data.eastmoney.com/").utf8Text()).path("result").path("data");
        ArrayNode rows = MAPPER.createArrayNode();
        if (result.isArray()) {
            result.forEach(rows::add);
        }
        return rows;
    }

    private Provenance provenance(URI uri) {
        return new Provenance(ProviderId.EASTMONEY.displayName(), uri, null, clock.instant(), false, null);
    }

    private void ensureLiveClient() {
        if (http == null) {
            throw new IllegalStateException("Live Eastmoney client is not configured");
        }
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        if (node.isArray()) {
            return node;
        }
        if (node.isObject()) {
            List<JsonNode> values = new ArrayList<>();
            node.elements().forEachRemaining(values::add);
            return values;
        }
        return List.of();
    }

    private static String unwrapJsonp(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        int start = trimmed.indexOf('(');
        int end = trimmed.lastIndexOf(')');
        return start >= 0 && end > start ? trimmed.substring(start + 1, end) : trimmed;
    }

    private static String stripHtml(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", "");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null : new BigDecimal(value.asText());
    }

    private static BigDecimal number(String value) {
        return value == null || value.isBlank() || "-".equals(value) ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.substring(0, 10));
    }

    private static LocalDateTime dateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() >= 19 ? LocalDateTime.parse(value.substring(0, 19), DATE_TIME)
                : date(value).atStartOfDay();
    }
}
