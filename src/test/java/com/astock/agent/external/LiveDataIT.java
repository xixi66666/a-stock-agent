package com.astock.agent.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.ResearchAggregationService;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.validation.MarketDataValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("external")
@SpringBootTest(properties = "spring.ai.model.chat=none")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveDataIT {

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    @Autowired
    private ResearchAggregationService service;

    private final MarketDataValidator validator = new MarketDataValidator();

    @Autowired
    private ObjectMapper objectMapper;

    private final List<Map<String, Object>> records = new ArrayList<>();

    @ParameterizedTest(name = "{0} returns sane recent public data")
    @ValueSource(strings = {"600519", "000001", "300750"})
    void liveSourcesReturnSaneRecentData(String code) {
        SecurityId security = SecurityId.parse(code);
        service.invalidate(security);

        StockResearchSnapshot snapshot = service.research(security);

        assertThat(snapshot.quote().payload()).isPresent().get()
                .extracting(quote -> quote.security().code())
                .isEqualTo(code);
        assertThat(snapshot.quote().payload().orElseThrow().price()).isPositive();
        List<DailyBar> bars = snapshot.bars().payload().orElseThrow();
        assertThat(bars).hasSizeGreaterThanOrEqualTo(260);
        assertThat(validator.validateBars(bars)).isEmpty();
        assertThat(snapshot.technical().payload()).isPresent();

        records.add(reportRecord(snapshot));
    }

    @AfterAll
    void writeEvidenceReport() throws IOException {
        Path directory = Path.of("target", "data-verification");
        Files.createDirectories(directory);
        String stamp = FILE_TIME.format(Instant.now());
        Path json = directory.resolve("verification-" + stamp + ".json");
        Path markdown = directory.resolve("verification-" + stamp + ".md");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), Map.of(
                "generatedAt", Instant.now().toString(),
                "securities", records));
        Files.writeString(markdown, markdownReport(), StandardCharsets.UTF_8);
    }

    private Map<String, Object> reportRecord(StockResearchSnapshot snapshot) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("security", snapshot.security().code());
        report.put("quote", sectionRecord(snapshot.quote()));
        report.put("bars", sectionRecord(snapshot.bars()));
        report.put("technical", sectionRecord(snapshot.technical()));
        report.put("sectors", sectionRecord(snapshot.sectors()));
        report.put("fundFlow", sectionRecord(snapshot.fundFlow()));
        report.put("capital", sectionRecord(snapshot.capital()));
        report.put("fundamentals", sectionRecord(snapshot.fundamentals()));
        report.put("research", sectionRecord(snapshot.research()));
        report.put("news", sectionRecord(snapshot.news()));
        report.put("announcements", sectionRecord(snapshot.announcements()));
        report.put("barCount", snapshot.bars().payload().map(List::size).orElse(0));
        report.put("latestTradeDate", snapshot.bars().payload()
                .filter(values -> !values.isEmpty()).map(values -> values.getLast().date()).orElse(null));
        report.put("crossSourceConsistent", snapshot.crossSourceConsistent());
        report.put("qualityScore", snapshot.quality().total());
        return report;
    }

    private static Map<String, Object> sectionRecord(com.astock.agent.marketdata.model.DataSection<?> section) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", section.status().name());
        result.put("provider", section.provenance().map(source -> source.provider()).orElse(null));
        result.put("sourceTime", section.provenance().map(source -> source.providerTimestamp()).orElse(null));
        result.put("fetchedAt", section.provenance().map(source -> source.fetchedAt()).orElse(null));
        result.put("fallback", section.provenance().map(source -> source.fallbackProvider()).orElse(null));
        result.put("validationIssues", section.issues());
        return result;
    }

    private String markdownReport() {
        StringBuilder output = new StringBuilder("# A 股真实数据验证报告\n\n");
        output.append("生成时间：").append(Instant.now()).append("\n\n");
        output.append("| 股票 | 行情源 | 行情状态 | K线数量 | 最近交易日 | 跨源一致 | 质量分 |\n");
        output.append("|---|---|---:|---:|---|---:|---:|\n");
        for (Map<String, Object> record : records) {
            @SuppressWarnings("unchecked")
            Map<String, Object> quote = (Map<String, Object>) record.get("quote");
            output.append("|").append(record.get("security"))
                    .append("|").append(quote.get("provider"))
                    .append("|").append(quote.get("status"))
                    .append("|").append(record.get("barCount"))
                    .append("|").append(record.get("latestTradeDate"))
                    .append("|").append(record.get("crossSourceConsistent"))
                    .append("|").append(record.get("qualityScore")).append("|\n");
        }
        output.append("\n可选来源允许降级；行情身份、K线数量与 OHLC 合法性是强制通过项。\n");
        return output.toString();
    }
}
