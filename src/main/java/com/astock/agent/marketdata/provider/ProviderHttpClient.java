package com.astock.agent.marketdata.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final HttpClient authenticatedClient;
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
        this.authenticatedClient = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
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

    /** Internal provider boundary only; credentials never enter URLs or exception messages. */
    public ProviderResponse getWithApiKey(ProviderId provider, URI uri, String apiKey) {
        return send(provider, uri, () -> baseRequest(uri).header("X-api-key", apiKey).GET().build());
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
                HttpRequest request = requestFactory.get();
                HttpClient transport = request.headers().firstValue("X-api-key").isPresent()
                        ? authenticatedClient : client;
                HttpResponse<byte[]> response = sendBounded(transport, request, uri);
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
            }
            backoff(attempt);
        }
        throw new IllegalStateException("Unreachable retry loop");
    }

    /**
     * 限时交换：{@link HttpRequest#timeout} 只约束响应头，响应体中途停滞时同步
     * {@code send} 会永久阻塞；这里用 {@code sendAsync} 加整体超时兜底，超时按 IO 失败重试。
     */
    private HttpResponse<byte[]> sendBounded(HttpClient transport, HttpRequest request, URI uri)
            throws IOException {
        CompletableFuture<HttpResponse<byte[]>> exchange =
                transport.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            return exchange.orTimeout(Math.max(1L, requestTimeout.toMillis()), TimeUnit.MILLISECONDS).join();
        } catch (CompletionException exception) {
            exchange.cancel(true);
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof HttpTimeoutException timeout) {
                throw timeout;
            }
            if (cause instanceof IOException failure) {
                throw failure;
            }
            if (cause instanceof TimeoutException) {
                throw new HttpTimeoutException(
                        "Provider response exceeded " + requestTimeout.toMillis() + "ms: " + redact(uri));
            }
            throw new IOException("Provider exchange failed: " + redact(uri), cause);
        }
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
