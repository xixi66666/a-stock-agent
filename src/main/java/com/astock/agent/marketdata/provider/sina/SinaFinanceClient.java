package com.astock.agent.marketdata.provider.sina;

import com.astock.agent.marketdata.model.FundFlow;
import com.astock.agent.marketdata.model.FundamentalData;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.ProviderHttpClient;
import com.astock.agent.marketdata.provider.ProviderId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SinaFinanceClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ProviderHttpClient http;
    private final Clock clock;

    public SinaFinanceClient() {
        this(null, Clock.systemUTC());
    }

    public SinaFinanceClient(ProviderHttpClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    public FundamentalData parseStatements(String body) {
        try {
            JsonNode reports = MAPPER.readTree(body).path("result").path("data").path("report_list");
            String latest = null;
            var fields = reports.fieldNames();
            while (fields.hasNext()) {
                String candidate = fields.next();
                if (latest == null || candidate.compareTo(latest) > 0) {
                    latest = candidate;
                }
            }
            if (latest == null || latest.length() != 8) {
                throw new IllegalArgumentException("Sina response contains no report period");
            }
            Map<String, BigDecimal> metrics = new LinkedHashMap<>();
            Map<String, BigDecimal> yearOverYear = new LinkedHashMap<>();
            for (JsonNode item : reports.path(latest).path("data")) {
                String title = item.path("item_title").asText();
                BigDecimal value = decimalOrNull(item.path("item_value"));
                if (title.isBlank() || value == null) {
                    continue;
                }
                metrics.put(title, value);
                BigDecimal yoy = decimalOrNull(item.path("item_tongbi"));
                if (yoy != null) {
                    yearOverYear.put(title, yoy);
                }
            }
            LocalDate period = LocalDate.of(
                    Integer.parseInt(latest.substring(0, 4)),
                    Integer.parseInt(latest.substring(4, 6)),
                    Integer.parseInt(latest.substring(6, 8)));
            return new FundamentalData(period, metrics, yearOverYear);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse Sina statements", exception);
        }
    }

    public List<FundFlow> parseFundFlow(String body) {
        try {
            List<FundFlow> result = new ArrayList<>();
            for (JsonNode item : MAPPER.readTree(body)) {
                result.add(new FundFlow(
                        LocalDate.parse(item.path("opendate").asText()),
                        decimal(item, "netamount"), null, null, null, null, "Sina Finance"));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse Sina fund flow", exception);
        }
    }

    public DataSection<FundamentalData> fetchStatements(SecurityId security) {
        ensureLiveClient();
        String paperCode = (security.exchange() == com.astock.agent.marketdata.model.Exchange.SHANGHAI ? "sh" : "sz")
                + security.code();
        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        Map<String, BigDecimal> yoy = new LinkedHashMap<>();
        LocalDate period = null;
        URI firstUri = null;
        try {
            for (String type : List.of("lrb", "fzb", "llb")) {
                URI uri = URI.create("https://quotes.sina.cn/cn/api/openapi.php/"
                        + "CompanyFinanceService.getFinanceReport2022?paperCode=" + paperCode
                        + "&source=" + type + "&type=0&page=1&num=8");
                if (firstUri == null) {
                    firstUri = uri;
                }
                FundamentalData parsed = parseStatements(http.get(
                        ProviderId.SINA, uri, "https://finance.sina.com.cn/").utf8Text());
                if (period == null || parsed.reportPeriod().isAfter(period)) {
                    period = parsed.reportPeriod();
                }
                metrics.putAll(parsed.metrics());
                yoy.putAll(parsed.yearOverYearPercent());
            }
            return DataSection.healthy(
                    new FundamentalData(period, metrics, yoy),
                    new Provenance(ProviderId.SINA.displayName(), firstUri, null, clock.instant(), false, null));
        } catch (Exception exception) {
            return DataSection.unavailable("Sina statements failed: " + exception.getMessage());
        }
    }

    public DataSection<List<FundFlow>> fetchFundFlow(SecurityId security) {
        ensureLiveClient();
        String prefix = security.exchange() == com.astock.agent.marketdata.model.Exchange.SHANGHAI ? "sh" : "sz";
        URI uri = URI.create("https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
                + "MoneyFlow.ssl_qsfx_zjlrqs?page=1&num=60&sort=opendate&asc=0&daima="
                + prefix + security.code());
        try {
            return DataSection.healthy(
                    parseFundFlow(http.get(ProviderId.SINA, uri, "https://finance.sina.com.cn/").utf8Text()),
                    new Provenance(ProviderId.SINA.displayName(), uri, null, clock.instant(), false, null));
        } catch (Exception exception) {
            return DataSection.unavailable("Sina fund flow failed: " + exception.getMessage());
        }
    }

    private void ensureLiveClient() {
        if (http == null) {
            throw new IllegalStateException("Live Sina client is not configured");
        }
    }

    private static BigDecimal decimal(JsonNode item, String field) {
        return decimalOrNull(item.path(field));
    }

    private static BigDecimal decimalOrNull(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        if (text.isEmpty() || "-".equals(text) || "--".equals(text) || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return new BigDecimal(text.replace(",", ""));
    }
}
