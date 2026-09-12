package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.*;
import com.astock.agent.marketdata.provider.BenchmarkDataGateway;
import com.astock.agent.marketdata.provider.hithink.HithinkFinanceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class HithinkBenchmarkGatewayTest {
    @Test void benchmarkUsesHithinkFirstAndRetainsFallbackSourceOnFailure() {
        var client = mock(HithinkFinanceClient.class);
        var backup = mock(BenchmarkDataGateway.class);
        var source = new Provenance("HiThink Finance",URI.create("https://fuyao.aicubes.cn/"),null,Instant.now(),false,null);
        var bars = DataSection.healthy(List.of(new DailyBar(LocalDate.of(2026,9,11),BigDecimal.TEN,
                BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,null)),source);
        when(client.fetchBenchmarkBars(BenchmarkId.CSI_300)).thenReturn(bars,DataSection.unavailable("failed"));
        when(backup.bars(BenchmarkId.CSI_300)).thenReturn(bars);
        var gateway = new HithinkBenchmarkGateway(client,backup);
        assertThat(gateway.bars(BenchmarkId.CSI_300)).isEqualTo(bars);
        verifyNoInteractions(backup);
        assertThat(gateway.bars(BenchmarkId.CSI_300).provenance().orElseThrow().fallbackProvider()).isEqualTo("HiThink Finance");
    }
}
