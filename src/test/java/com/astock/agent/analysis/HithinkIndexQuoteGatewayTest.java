package com.astock.agent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.*;
import com.astock.agent.marketdata.provider.*;
import com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class HithinkIndexQuoteGatewayTest {
    private HttpServer server;
    private HithinkFinanceClient client;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger fallbackCalls = new AtomicInteger();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);
    private final List<BenchmarkId> three = List.of(BenchmarkId.SHANGHAI_COMPOSITE,
            BenchmarkId.SHENZHEN_COMPONENT, BenchmarkId.CHI_NEXT);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var http = new ProviderHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(2), 0,
                new ProviderThrottle(Duration.ZERO, Duration.ZERO),
                new ProviderHealthRegistry(Duration.ofSeconds(1), clock), clock);
        client = new HithinkFinanceClient(http, clock, "test-only-key",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void healthyPrimaryNeverCallsFallback() {
        body.set(snapshotBody("000001.SH", "399001.SZ", "399006.SZ"));
        var gateway = new HithinkIndexQuoteGateway(client, benchmark -> {
            fallbackCalls.incrementAndGet();
            return DataSection.unavailable("不应被调用");
        });
        var sections = gateway.quotes(three);
        assertThat(fallbackCalls).hasValue(0);
        assertThat(sections).hasSize(3);
        assertThat(sections.get(0).quote().status()).isEqualTo(SectionStatus.UNVERIFIED);
        assertThat(sections.get(0).benchmark()).isEqualTo(BenchmarkId.SHANGHAI_COMPOSITE);
        assertThat(sections.get(0).quote().provenance().orElseThrow().provider()).isEqualTo("HiThink Finance");
    }

    @Test void failedPrimaryFallsBackToLatestTwoBenchmarkBars() {
        body.set("{\"code\":1,\"message\":\"denied\"}");
        var gateway = new HithinkIndexQuoteGateway(client, benchmark -> DataSection.healthy(
                List.of(bar("2026-09-10", "3000"), bar("2026-09-11", "3030")), tencentSource()));

        var sections = gateway.quotes(List.of(BenchmarkId.SHANGHAI_COMPOSITE));
        var quote = sections.getFirst().quote().payload().orElseThrow();
        assertThat(sections.getFirst().quote().status()).isEqualTo(SectionStatus.UNVERIFIED);
        assertThat(quote.lastPoint()).isEqualByComparingTo("3030");
        assertThat(quote.previousClose()).isEqualByComparingTo("3000");
        assertThat(quote.changeAmount()).isEqualByComparingTo("30");
        assertThat(quote.changePercent()).isEqualByComparingTo("1.0000");
        assertThat(sections.getFirst().quote().issues())
                .anyMatch(s -> s.contains("收盘口径")).anyMatch(s -> s.contains("2026-09-11"));
        assertThat(sections.getFirst().quote().provenance().orElseThrow().fallbackProvider())
                .isEqualTo("HiThink Finance");
    }

    @Test void bothSourcesUnavailableStayUnavailableWithBothReasons() {
        body.set("{\"code\":1,\"message\":\"denied\"}");
        var gateway = new HithinkIndexQuoteGateway(client,
                benchmark -> DataSection.unavailable("腾讯日线不可用"));
        var section = gateway.quotes(List.of(BenchmarkId.SHANGHAI_COMPOSITE)).getFirst().quote();
        assertThat(section.status()).isEqualTo(SectionStatus.UNAVAILABLE);
        assertThat(section.issues()).anyMatch(s -> s.contains("腾讯日线不可用"))
                .anyMatch(s -> s.contains("同花顺"));
    }

    @Test void partialPrimaryFallsBackOnlyForTheMissingIndex() {
        body.set(snapshotBody("000001.SH", "399001.SZ"));
        var gateway = new HithinkIndexQuoteGateway(client, benchmark -> {
            fallbackCalls.incrementAndGet();
            return DataSection.healthy(List.of(bar("2026-09-10", "2000"), bar("2026-09-11", "1980")),
                    tencentSource());
        });
        var sections = gateway.quotes(three);
        assertThat(fallbackCalls).hasValue(1);
        assertThat(sections.get(0).quote().status()).isEqualTo(SectionStatus.UNVERIFIED);
        assertThat(sections.get(0).quote().provenance().orElseThrow().provider()).isEqualTo("HiThink Finance");
        assertThat(sections.get(2).benchmark()).isEqualTo(BenchmarkId.CHI_NEXT);
        assertThat(sections.get(2).quote().payload().orElseThrow().lastPoint()).isEqualByComparingTo("1980");
        assertThat(sections.get(2).quote().provenance().orElseThrow().fallbackProvider())
                .isEqualTo("HiThink Finance");
    }

    private static String snapshotBody(String... codes) {
        var rows = java.util.Arrays.stream(codes).map(code ->
                "{\"thscode\":\"" + code + "\",\"last_price\":3100,\"prev_price\":3080,"
                        + "\"price_change\":20,\"price_change_ratio_pct\":0.65}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"code\":0,\"data\":{\"timestamp\":1789081200000,\"item\":[" + rows + "]}}";
    }

    private static DailyBar bar(String date, String close) {
        return new DailyBar(LocalDate.parse(date), new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal("1000"), null);
    }

    private static Provenance tencentSource() {
        return new Provenance("Tencent", URI.create("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"),
                null, Instant.parse("2026-09-12T00:00:00Z"), false, null);
    }
}
