package com.astock.agent.agent.financial;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialTrendResult;
import com.astock.agent.analysis.financial.FinancialTrendResult.TrendSeries;
import com.astock.agent.marketdata.model.Exchange;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialDeterministicComposerTest {

    private final FinancialDeterministicComposer composer = new FinancialDeterministicComposer();

    @Test
    void fallbackTextContainsScoreTierAndSignals() {
        FinancialEvidencePackage pack = new FinancialEvidencePackage(
                "600519",
                new FinancialStatementHistory(new SecurityId("600519", Exchange.SHANGHAI), List.of()),
                new FinancialQualityScore(6, "良", 9, List.of(
                        new FinancialQualityScore.SignalResult(1, "TTM ROA 为正",
                                FinancialQualityScore.SignalStatus.PASS, "条件成立")), true),
                new FinancialTrendResult(List.of(new TrendSeries("营业总收入", "元", "CUMULATIVE",
                        List.of(new FinancialTrendResult.TrendPoint(LocalDate.parse("2026-06-30"), BigDecimal.valueOf(1))),
                        List.of(), "RISING", null, null)), 12),
                false, 6, 3, 0, 9);

        FinancialNarrative narrative = composer.compose(pack);

        assertThat(narrative.tierInterpretation()).contains("6 分").contains("良");
        assertThat(narrative.signalCommentary()).contains("通过");
        assertThat(narrative.trendCommentary()).contains("上升");
        assertThat(narrative.riskNotes()).isNotEmpty();
    }

    @Test
    void insufficientHistoryProducesInsufficientText() {
        FinancialEvidencePackage pack = new FinancialEvidencePackage(
                "600519",
                new FinancialStatementHistory(new SecurityId("600519", Exchange.SHANGHAI), List.of()),
                FinancialQualityScore.insufficient(),
                new FinancialTrendResult(List.of(), 3),
                false, 0, 0, 0, 0);

        FinancialNarrative narrative = composer.compose(pack);

        assertThat(narrative.tierInterpretation()).contains("数据不足");
    }
}
