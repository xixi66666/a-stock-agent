package com.astock.agent.marketdata.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 所有 Provider 共用的 HTTP 边界。
 *
 * <p>统一处理连接/请求超时、有限重试、Provider 限流、冷却状态和敏感 URI 脱敏。
 * Provider 适配器只负责构造请求和解析响应，不应该各自实现一套重试策略。</p>
 */
public final class ProviderHttpClient {

    private static final Set<String> SENSITIVE_MARKERS = Set.of("key", "token", "secret", "authorization");
    private final HttpClient client;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final ProviderThrottle throttle;
    private final ProviderHealthRegistry healthRegistry;
    private final Clock clock;

    public ProviderHttpClient(
            Duration connectTimeout,
            Duration requestTimeout,
            int maxRetries,
            ProviderThrottle throttle,
            ProviderHealthRegistry healthRegistry,
            Clock clock) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.requestTimeout = requestTimeout;
        this.maxRetries = Math.max(0, Math.min(maxRetries, 2));
        this.throttle = throttle;
        this.healthRegistry = healthRegistry;
        this.clock = clock;
    }

    public ProviderResponse get(ProviderId provider, URI uri, String referer) {
        return send(provider, uri, () -> {
            HttpRequest.Builder builder = baseRequest(uri).GET();
            if (referer != null && !referer.isBlank()) {
                builder.header("Referer", referer);
            }
            return builder.build();
        });
    }

    public ProviderResponse post(ProviderId provider, URI uri, String contentType, byte[] body, String referer) {
        return send(provider, uri, () -> {
            HttpRequest.Builder builder = baseRequest(uri)
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (referer != null && !referer.isBlank()) {
                builder.header("Referer", referer);
            }
            return builder.build();
        });
    }

    private ProviderResponse send(ProviderId provider, URI uri, Supplier<HttpRequest> requestFactory) {
        // 先检查 Provider 是否处于冷却，再通过共享 throttle 发出请求，避免并发绕过限流。
        if (!healthRegistry.availability(provider).available()) {
            throw new ProviderException(provider, uri, 403, provider.displayName() + " is in cooldown");
        }
        try {
            return throttle.call(provider, () -> sendWithRetries(provider, uri, requestFactory));
        } catch (ProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            Thread.currentThread().interrupt();
            throw new ProviderException(provider, uri, "Provider request interrupted", exception);
        }
    }

    private ProviderResponse sendWithRetries(
            ProviderId provider, URI uri, Supplier<HttpRequest> requestFactory) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<byte[]> response = client.send(
                        requestFactory.get(), HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    healthRegistry.recordSuccess(provider, clock.instant());
                    return new ProviderResponse(uri, status, response.headers(), response.body());
                }
                if (status == 403) {
                    healthRegistry.recordBlocked(provider, clock.instant());
                    throw new ProviderException(provider, uri, status, "Provider rejected request: " + redact(uri));
                }
                if (!isRetriable(status) || attempt == maxRetries) {
                    throw new ProviderException(provider, uri, status, "Provider HTTP " + status + ": " + redact(uri));
                }
            } catch (IOException exception) {
                if (attempt == maxRetries) {
                    throw new ProviderException(provider, uri, "Provider connection failed: " + redact(uri), exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ProviderException(provider, uri, "Provider request interrupted", exception);
            }
            backoff(attempt);
        }
        throw new IllegalStateException("Unreachable retry loop");
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json,text/plain,*/*")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AStockAgent/0.1");
    }

    private static boolean isRetriable(int status) {
        return status == 429 || status >= 500;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Duration.ofMillis(200L << attempt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public static String redact(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return uri.toString();
        }
        StringBuilder safe = new StringBuilder();
        for (String pair : query.split("&")) {
            if (!safe.isEmpty()) {
                safe.append('&');
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String lower = name.toLowerCase(Locale.ROOT);
            boolean sensitive = SENSITIVE_MARKERS.stream().anyMatch(lower::contains);
            safe.append(name);
            if (equals >= 0) {
                safe.append('=').append(sensitive ? "REDACTED" : pair.substring(equals + 1));
            }
        }
        try {
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), safe.toString(), uri.getFragment()).toString();
        } catch (Exception ignored) {
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        }
    }
}
