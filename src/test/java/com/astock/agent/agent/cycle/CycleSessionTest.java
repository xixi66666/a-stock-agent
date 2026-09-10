package com.astock.agent.agent.cycle;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.*;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CycleSessionTest {
    @Test
    void evidencePreservesReadableDatesAndQualityStates() {
        Instant at = Instant.parse("2026-09-10T02:00:00Z");
        var snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519")).withBars(
                DataSection.stale(List.of(), new Provenance("fixture", URI.create("https://example.com/bars"), at, at, true, "backup"), List.of("历史数据")));
        var session = new CycleSession(mock(CycleLibrary.class), snapshot, stage -> {});
        var evidence = session.cycleReadEvidence("bars").data();
        assertThat(evidence.path("status").asText()).isEqualTo("STALE");
        assertThat(evidence.path("provenance").path("fetchedAt").asText()).isEqualTo("2026-09-10T02:00:00Z");
        assertThat(evidence.path("provenance").path("cached").asBoolean()).isTrue();
    }
}
