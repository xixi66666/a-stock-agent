package com.astock.agent.agent.quant;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuantNarrativeValidatorTest {
    @Test
    void rejectsUnsupportedNumbersTradeInstructionsAndScores() {
        QuantReportFacts facts = new QuantReportFacts(
                Map.of("return-20", new MetricObservation("return-20", new BigDecimal("29.0"), "%", "20日", null,
                        MetricAvailability.AVAILABLE, "test", java.util.List.of("bars"), java.util.List.of())),
                Map.of(), java.util.List.of(), java.util.List.of("bars"), java.util.List.of());

        QuantNarrativeValidator.Validation result = new QuantNarrativeValidator().validate(
                new QuantNarrativeDraft("目标价 999", "", "综合得分 80", "买入", "", "", ""), facts);

        assertThat(result.blockingIssues())
                .contains("UNSUPPORTED_NUMBER", "TRADE_INSTRUCTION", "SCORING_CONTENT");
    }

    @Test
    void allowsFactsBackedPlainResearchText() {
        QuantReportFacts facts = new QuantReportFacts(
                Map.of("return-20", new MetricObservation("return-20", new BigDecimal("29.0"), "%", "20日", null,
                        MetricAvailability.AVAILABLE, "test", java.util.List.of("bars"), java.util.List.of())),
                Map.of(), java.util.List.of(), java.util.List.of("bars"), java.util.List.of());

        QuantNarrativeValidator.Validation result = new QuantNarrativeValidator().validate(
                new QuantNarrativeDraft("20日收益为29.0%，数据来自 bars。", "", "", "", "", "", ""), facts);

        assertThat(result.blockingIssues()).isEmpty();
    }
}
