package com.astock.agent.agent.overall;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

class RobustOverallReportDraftConverterTest {

    private final RobustOverallReportDraftConverter converter = new RobustOverallReportDraftConverter();

    @Test
    void staysABeanOutputConverterSoStructuredRequestsKeepTheirSchema() {
        assertThat(converter).isInstanceOf(BeanOutputConverter.class);
        assertThat(converter.getFormat()).contains("JSON");
        assertThat(converter.getJsonSchema()).contains("overallConclusion", "sourceReferences", "disclaimer");
    }

    @Test
    void convertsFencedResponseThroughRobustParser() {
        OverallReportDraft draft = converter.convert("```json\n{\"overallConclusion\": \"结论\"}\n```");

        assertThat(draft.overallConclusion()).isEqualTo("结论");
    }
}
