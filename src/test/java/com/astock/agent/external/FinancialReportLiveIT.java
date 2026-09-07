package com.astock.agent.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.agent.financial.FinancialReportAnalysis;
import com.astock.agent.agent.financial.FinancialReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("external")
@SpringBootTest(properties = "spring.ai.model.chat=none")
class FinancialReportLiveIT {

    @Autowired
    private FinancialReportService service;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void generatesLiveFinancialReportFromSinaHistory() throws IOException {
        FinancialReportAnalysis analysis = service.generate("600519");

        assertThat(analysis.periodCount()).isGreaterThanOrEqualTo(8);
        assertThat(analysis.latestPeriod().payload()).isPresent();
        assertThat(analysis.latestPeriod().provenance()).isPresent();
        assertThat(analysis.latestPeriod().payload().orElseThrow().reportPeriod())
                .isEqualTo(analysis.trends().series().getFirst().points().getLast().period());
        assertThat(analysis.qualityScore().sufficientData()).isTrue();
        assertThat(analysis.qualityScore().signals()).hasSize(9);
        assertThat(analysis.trends().series()).hasSize(8);
        assertThat(analysis.disclaimer())
                .isEqualTo(FinancialReportAnalysis.REQUIRED_DISCLAIMER);

        Path directory = Path.of("target", "data-verification");
        Files.createDirectories(directory);
        Path output = directory.resolve("financial-report-600519.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), analysis);
    }
}
