package com.astock.agent.agent.finrobot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.model.AiModelProperties;
import com.astock.agent.analysis.ResearchGateway;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 本地 HTTP 模型桩 + 真实 Python/官方 SDK；不访问任何外部服务。 */
@EnabledIfSystemProperty(named = "finrobot.python", matches = ".+")
class OfficialFinRobotWorkerIntegrationTest {
    @Test void javaStartsOfficialPythonSdkAndProducesEightSectionsWithoutPersistingCredentials() throws Exception {
        var mapper = new ObjectMapper();
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var body = mapper.readTree(exchange.getRequestBody());
            var prompt = body.path("messages").get(0).path("content").asText();
            var match = Pattern.compile("JSON 字段名：(\\w+)").matcher(prompt);
            if (!match.find()) { exchange.sendResponseHeaders(400, -1); exchange.close(); return; }
            requests.incrementAndGet();
            var response = Map.of("id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture",
                    "choices", java.util.List.of(Map.of("index", 0, "finish_reason", "stop", "message",
                            Map.of("role", "assistant", "content", mapper.writeValueAsString(Map.of(match.group(1), "缺少证据，暂不可确认。"))))));
            byte[] bytes = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            Path reports = Path.of("target", "finrobot-integration-" + java.util.UUID.randomUUID());
            var properties = new OfficialFinRobotProperties("official", System.getProperty("finrobot.python"),
                    reports.toString(), Duration.ofSeconds(90), 1);
            var security = SecurityId.parse("600519");
            var gateway = mock(ResearchGateway.class);
            // 健康财报覆盖 LocalDate -> Python 的 ISO 日期边界，报告期不能被序列化成时间戳数组。
            var periods = java.util.List.of(
                    new FinancialPeriodStatement(java.time.LocalDate.parse("2024-12-31"),
                            new java.math.BigDecimal("100"), new java.math.BigDecimal("70"),
                            null, null, null, null, null, null, null, null, null),
                    new FinancialPeriodStatement(java.time.LocalDate.parse("2025-12-31"),
                            new java.math.BigDecimal("120"), new java.math.BigDecimal("0"),
                            null, null, null, null, null, null, null, null, null));
            var provenance = new Provenance("fixture", java.net.URI.create("https://example.org/report"),
                    null, java.time.Instant.parse("2026-09-14T00:00:00Z"), false, null);
            when(gateway.financialHistory(security)).thenReturn(
                    DataSection.healthy(new FinancialStatementHistory(security, periods), provenance));
            var worker = new OfficialFinRobotWorker(properties, new StockAgentTools(StockResearchSnapshot::empty), gateway);
            var model = new AiModelProperties.Model(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                    "private-fixture-marker", "/v1/chat/completions", "fixture-model", 0.2);
            Path output = reports.resolve("task");
            var report = worker.run("600519", model, output);
            assertThat(report.path("sections").size()).isEqualTo(8);
            assertThat(report.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(report.path("computedMetrics").path("status").asText()).isEqualTo("DEGRADED");
            assertThat(report.path("computedMetrics").path("rows").size()).isGreaterThan(0);
            assertThat(requests.get()).isEqualTo(8);
            assertThat(Files.readString(output.resolve("report.html"))).contains("公司概览", "竞争分析");
            for (String file : java.util.List.of("bundle.json", "evidence.json", "progress.json", "report.html")) {
                assertThat(Files.readString(output.resolve(file))).doesNotContain("private-fixture-marker", "apiKey");
            }
        } finally { server.stop(0); }
    }
}
