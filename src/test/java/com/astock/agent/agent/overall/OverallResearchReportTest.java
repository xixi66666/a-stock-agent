package com.astock.agent.agent.overall;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.FundFlowSummary;
import com.astock.agent.marketdata.model.FundFlowWindowSummary;
import com.astock.agent.marketdata.model.IndustryPeerComparison;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.PeerSelectionReason;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OverallResearchReportTest {

    @Test
    void preservesModelDisclaimerWithoutServerOverride() {
        OverallReportDraft draft = new OverallReportDraft(
                "结论", "数据质量", "公司与基本面", "技术与资金", "估值与行业", "事件与情绪",
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), "模型自定义说明");

        OverallResearchReport report = OverallResearchReport.from(
                draft, "deepseek-chat", Instant.EPOCH, Instant.EPOCH, "overall-v1");

        assertThat(report.disclaimer()).isEqualTo("模型自定义说明");
    }

    @Test
    void copiesStructuredMarketDetailsFromSnapshotInsteadOfDraft() {
        StockResearchSnapshot snapshot = enrichedSnapshot();
        OverallResearchReport report = OverallResearchReport.fromSnapshot(
                draft(), "mimo-v2.5-pro", snapshot, Instant.EPOCH, "overall-v3");

        assertThat(report.industryValuation()).isEqualTo(snapshot.industryValuation());
        assertThat(report.fundFlowSummary()).isEqualTo(snapshot.fundFlowSummary());
    }

    private static OverallReportDraft draft() {
        return new OverallReportDraft(
                "结论", "数据质量", "公司与基本面", "技术与资金", "估值与行业", "事件与情绪",
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), "仅供学习研究，不构成投资建议");
    }

    private static StockResearchSnapshot enrichedSnapshot() {
        SecurityId id = SecurityId.parse("600519");
        StockResearchSnapshot base = StockResearchSnapshot.empty(id);
        Provenance source = new Provenance("fixture", URI.create("https://example.com/derived"), null,
                Instant.EPOCH, false, null);
        IndustryPeerComparison peer = new IndustryPeerComparison(
                "000858", "五粮液", new BigDecimal("18"), new BigDecimal("4"),
                new BigDecimal("700000000000"), new BigDecimal("11.11"), new BigDecimal("25"),
                List.of(PeerSelectionReason.MARKET_CAP_NEARBY));
        IndustryValuationData valuation = new IndustryValuationData(
                "BK0477", "白酒", 2, 2, 0, 2, 0,
                new BigDecimal("20"), new BigDecimal("19"), new BigDecimal("100"),
                new BigDecimal("5"), new BigDecimal("4.5"), new BigDecimal("100"), List.of(peer));
        FundFlowSummary summary = new FundFlowSummary(
                LocalDate.of(2026, 7, 15),
                new FundFlowWindowSummary(1, 1, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE.negate(), BigDecimal.ONE.negate()),
                new FundFlowWindowSummary(5, 5, new BigDecimal("50"), new BigDecimal("20"),
                        new BigDecimal("30"), new BigDecimal("-10"), new BigDecimal("-15")),
                new FundFlowWindowSummary(20, 18, new BigDecimal("120"), new BigDecimal("70"),
                        new BigDecimal("50"), new BigDecimal("-30"), new BigDecimal("-40")));
        return new StockResearchSnapshot(
                base.security(), base.quote(), base.bars(), base.technical(), base.sectors(),
                DataSection.healthy(valuation, source), base.fundFlow(), DataSection.healthy(summary, source),
                base.capital(), base.fundamentals(), base.research(), base.news(), base.announcements(),
                base.quality(), base.crossSourceConsistent(), base.coreCompleteness(),
                base.authoritativeSources(), base.fetchedAt());
    }
}
