package com.astock.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BookKnowledgeServiceTest {
    private final BookKnowledgeService service = new BookKnowledgeService();

    @Test
    void exposesImplementedNisonCardsAndSeparatesReferenceOnlyMethods() {
        assertThat(service.forOverallReport())
                .extracting(KnowledgeEntry::id)
                .contains("nison-candle-anatomy", "nison-moving-average", "nison-oscillator", "nison-volume")
                .doesNotContain("nison-continuation-patterns", "nison-retracement");
        assertThat(service.forOverallReport()).hasSize(14)
                .allMatch(entry -> entry.implementationStatus().equals("IMPLEMENTED"));

        assertThat(service.forCandlestick(List.of("HAMMER")))
                .extracting(KnowledgeEntry::id)
                .contains("nison-candle-anatomy", "nison-reversal", "nison-volume")
                .doesNotContain("nison-continuation-patterns", "nison-retracement");
    }
}
