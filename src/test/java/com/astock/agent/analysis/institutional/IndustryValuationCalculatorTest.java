package com.astock.agent.analysis.institutional;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.IndustryPeerQuote;
import com.astock.agent.marketdata.model.IndustryValuationData;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndustryValuationCalculatorTest {

    @Test
    void removesInvalidPeAndCalculatesInclusivePercentile() {
        List<IndustryPeerQuote> peers = List.of(
                peer("600001", "10", "1.0"),
                peer("600002", "20", "2.0"),
                peer("600519", "30", "3.0"),
                peer("600004", "-5", null));

        IndustryValuationData result = new IndustryValuationCalculator()
                .calculate("BK0477", "Liquor", "600519", peers);

        assertThat(result.validPeSamples()).isEqualTo(3);
        assertThat(result.peMedian()).isEqualByComparingTo("20");
        assertThat(result.pePercentile()).isEqualByComparingTo("100.00");
        assertThat(result.excludedPeSamples()).isEqualTo(1);
        assertThat(result.validPbSamples()).isEqualTo(3);
    }

    @Test
    void returnsNullMetricWhenTargetHasNoComparableValue() {
        IndustryValuationData result = new IndustryValuationCalculator().calculate(
                "BK0477", "Liquor", "600519",
                List.of(peer("600001", "10", "1.0"), peer("600519", null, null)));

        assertThat(result.targetPe()).isNull();
        assertThat(result.pePercentile()).isNull();
        assertThat(result.validPeSamples()).isEqualTo(1);
    }

    private static IndustryPeerQuote peer(String code, String pe, String pb) {
        return new IndustryPeerQuote(
                code, code, pe == null ? null : new BigDecimal(pe),
                pb == null ? null : new BigDecimal(pb), new BigDecimal("1000000"));
    }
}
