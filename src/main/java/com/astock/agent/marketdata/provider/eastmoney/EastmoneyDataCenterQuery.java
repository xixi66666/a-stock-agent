package com.astock.agent.marketdata.provider.eastmoney;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EastmoneyDataCenterQuery {

    private EastmoneyDataCenterQuery() {
    }

    public static URI create(String reportName, String filter, String sortColumns, int pageSize) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("reportName", reportName);
        parameters.put("columns", "ALL");
        parameters.put("filter", filter);
        parameters.put("pageNumber", "1");
        parameters.put("pageSize", Integer.toString(pageSize));
        parameters.put("sortColumns", sortColumns);
        parameters.put("sortTypes", "-1");
        parameters.put("source", "WEB");
        parameters.put("client", "WEB");
        String query = parameters.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        return URI.create("https://datacenter-web.eastmoney.com/api/data/v1/get?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
