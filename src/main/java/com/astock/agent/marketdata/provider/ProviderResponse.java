package com.astock.agent.marketdata.provider;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public record ProviderResponse(URI uri, int statusCode, HttpHeaders headers, byte[] body) {

    public String text(Charset fallback) {
        return new String(body, responseCharset(fallback));
    }

    public String utf8Text() {
        return text(StandardCharsets.UTF_8);
    }

    private Charset responseCharset(Charset fallback) {
        String contentType = headers.firstValue("Content-Type").orElse("");
        int marker = contentType.toLowerCase().indexOf("charset=");
        if (marker >= 0) {
            String name = contentType.substring(marker + 8).split("[;\\s]")[0].replace("\"", "");
            try {
                return Charset.forName(name);
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
