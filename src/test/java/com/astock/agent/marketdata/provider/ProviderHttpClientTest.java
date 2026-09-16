package com.astock.agent.marketdata.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderHttpClientTest {

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/stall", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 1_000_000);
            OutputStream body = exchange.getResponseBody();
            body.write(new byte[32]);
            body.flush();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stop() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void stalledResponseBodyDoesNotBlockTheCallerBeyondTheRequestTimeout() {
        var http = new ProviderHttpClient(Duration.ofSeconds(1), Duration.ofSeconds(1), 0,
                new ProviderThrottle(Duration.ZERO, Duration.ZERO),
                new ProviderHealthRegistry(Duration.ofSeconds(1), clock), clock);
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stall");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            long started = System.nanoTime();
            ProviderException failure = assertThrows(ProviderException.class,
                    () -> http.get(ProviderId.CNINFO, uri, null));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            assertThat(elapsedMillis).isLessThan(5_000);
            assertThat(failure.provider()).isEqualTo(ProviderId.CNINFO);
            assertThat(failure.getMessage()).contains("/stall");
        });
    }
}
