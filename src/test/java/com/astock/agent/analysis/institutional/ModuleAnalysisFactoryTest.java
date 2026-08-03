package com.astock.agent.analysis.institutional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.astock.agent.agent.report.ReportFact;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModuleAnalysisFactoryTest {

    @Test
    void moduleAnalysisCopiesCollectionsAndRejectsFactsWithoutEvidenceIdentity() {
        ReportFact fact = new ReportFact("technical-sma20", "SMA20", "100", "technical", null);
        AnalysisSignal signal = new AnalysisSignal("trend-confirmed", Direction.STRONGER, 60,
                "SMA20高于SMA60", "中期均线多头排列支持趋势延续",
                List.of(fact.id()), "SMA20跌破SMA60");
        List<ReportFact> facts = new ArrayList<>(List.of(fact));

        ModuleAnalysis result = new ModuleAnalysis(AnalysisModule.TECHNICAL_PRICE_VOLUME,
                Direction.STRONGER, "趋势与动量互相确认", "HIGH",
                facts, List.of(signal), List.of("趋势跟随与动量确认"),
                List.of("量能未同步放大"), List.of("仅基于日线样本"), List.of("technical"));
        facts.clear();

        assertThat(result.facts()).containsExactly(fact);
        assertThat(result.signals()).containsExactly(signal);
        assertThatThrownBy(() -> new ReportFact("", "SMA20", "100", "technical", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
