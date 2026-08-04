package com.astock.agent.analysis.institutional;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.IndustryPeerQuote;
import com.astock.agent.marketdata.model.IndustryPeerComparison;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.PeerSelectionReason;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndustryValuationCalculatorTest {

    @Test
    void removesInvalidPeAndCalculatesInclusivePercentile() {
        List<IndustryPeerQuote> peers = List.of(
                peer("600001", "10", "1.0", "90"),
                peer("600002", "20", "2.0", "95"),
                peer("600519", "30", "3.0", "100"),
                peer("600004", "-5", null, "80"));

        IndustryValuationData result = new IndustryValuationCalculator()
                .calculate("BK0477", "Liquor", "600519", peers).data();

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
                List.of(peer("600001", "10", "1.0", "90"), peer("600519", null, null, "100"))).data();

        assertThat(result.targetPe()).isNull();
        assertThat(result.pePercentile()).isNull();
        assertThat(result.validPeSamples()).isEqualTo(1);
    }

    @Test
    void selectsFiveNearestAndThreeIndustryLeadersWithStableOrder() {
        List<IndustryPeerQuote> peers = List.of(
                peer("600519", "30", "3", "100"),
                peer("N001", "20", "2", "99"),
                peer("N002", "21", "2.1", "101"),
                peer("N003", "22", "2.2", "95"),
                peer("N004", "23", "2.3", "105"),
                peer("N005", "24", "2.4", "90"),
                peer("L001", "25", "2.5", "1000"),
                peer("L002", "26", "2.6", "900"),
                peer("L003", "27", "2.7", "800"));

        IndustryValuationCalculation calculation = new IndustryValuationCalculator()
                .calculate("BK0477", "白酒", "600519", peers);

        assertThat(calculation.data().selectedPeers())
                .extracting(IndustryPeerComparison::code)
                .containsExactly("N001", "N002", "N003", "N004", "N005", "L001", "L002", "L003");
        assertThat(calculation.data().selectedPeers()).noneMatch(peer -> peer.code().equals("600519"));
        assertThat(calculation.issues()).isEmpty();
    }

    @Test
    void mergesSelectionReasonsAndDoesNotInventInvalidPremiums() {
        List<IndustryPeerQuote> peers = List.of(
                peer("600519", "30", "3", "100"),
                peer("BOTH", "15", null, "99"),
                peer("A", "10", "1", "80"),
                peer("B", "11", "1.1", "70"),
                peer("C", "12", "1.2", "60"),
                peer("D", "13", "1.3", "50"),
                peer("E", "14", "1.4", "40"));

        IndustryPeerComparison both = new IndustryValuationCalculator()
                .calculate("BK0477", "白酒", "600519", peers)
                .data().selectedPeers().stream()
                .filter(peer -> peer.code().equals("BOTH"))
                .findFirst().orElseThrow();

        assertThat(both.selectionReasons()).containsExactly(
                PeerSelectionReason.MARKET_CAP_NEARBY,
                PeerSelectionReason.INDUSTRY_LEADER);
        assertThat(both.targetPePremiumPercent()).isEqualByComparingTo("100");
        assertThat(both.targetPbPremiumPercent()).isNull();
    }

    @Test
    void excludesInvalidMarketValuesAndDoesNotPadAShortIndustry() {
        List<IndustryPeerQuote> peers = List.of(
                peer("600519", "30", "3", "100"),
                peer("VALID", "20", "2", "90"),
                peer("ZERO", "10", "1", "0"),
                peer("NEGATIVE", "10", "1", "-5"));

        IndustryValuationCalculation calculation = new IndustryValuationCalculator()
                .calculate("BK0477", "白酒", "600519", peers);

        assertThat(calculation.data().selectedPeers())
                .extracting(IndustryPeerComparison::code)
                .containsExactly("VALID");
    }

    private static IndustryPeerQuote peer(String code, String pe, String pb, String marketValue) {
        return new IndustryPeerQuote(
                code, code, pe == null ? null : new BigDecimal(pe),
                pb == null ? null : new BigDecimal(pb),
                marketValue == null ? null : new BigDecimal(marketValue));
    }
}
