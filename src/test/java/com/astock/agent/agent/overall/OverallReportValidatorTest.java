package com.astock.agent.agent.overall;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.SecurityId;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OverallReportValidatorTest {

    @Test
    void rejectsTradeInstructionUnknownSourceUnsupportedNumberAndWrongDisclaimer() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        OverallReportDraft draft = validDraft(
                "建议买入，目标价999元",
                List.of(new OverallSourceReference("quote", "invented-provider", null, null)),
                "免责声明缺失");

        OverallReportValidator.Validation result = new OverallReportValidator().validate(draft, snapshot);

        assertThat(result.issues()).contains(
                "TRADE_INSTRUCTION", "UNKNOWN_SOURCE_REFERENCE",
                "UNSUPPORTED_NUMBER", "INVALID_DISCLAIMER");
    }

    @Test
    void acceptsEvidenceBoundDraftAndPreservesUnavailableSections() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        OverallReportDraft draft = validDraft(
                "核心行情不可用，无法形成高强度判断",
                List.of(), "仅供学习研究，不构成投资建议");

        OverallReportValidator.Validation result = new OverallReportValidator().validate(draft, snapshot);

        assertThat(result.issues()).isEmpty();
    }

    @Test
    void acceptsSecurityCodeAndTimestampTokensPresentInSnapshotText() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        String fetchedAt = snapshot.fetchedAt().toString();
        OverallReportDraft draft = validDraft(
                "证券600519的快照时间为" + fetchedAt,
                List.of(), "仅供学习研究，不构成投资建议");

        OverallReportValidator.Validation result = new OverallReportValidator().validate(draft, snapshot);

        assertThat(result.issues()).isEmpty();
    }

    @Test
    void rejectsNumberFoundOnlyInSnapshotIssueText() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"))
                .withQuote(DataSection.unavailable("provider issue 123"));
        OverallReportDraft draft = validDraft(
                "来源问题编号123", List.of(), "仅供学习研究，不构成投资建议");

        OverallReportValidator.Validation result = new OverallReportValidator().validate(draft, snapshot);

        assertThat(result.issues()).contains("UNSUPPORTED_NUMBER");
    }

    @Test
    void doesNotAllowStandaloneYearFromSnapshotTimestamp() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        String year = snapshot.fetchedAt().toString().substring(0, 4);
        OverallReportDraft draft = validDraft(
                "快照年份" + year, List.of(), "仅供学习研究，不构成投资建议");

        OverallReportValidator.Validation result = new OverallReportValidator().validate(draft, snapshot);

        assertThat(result.issues()).contains("UNSUPPORTED_NUMBER");
    }

    @Test
    void nullSnapshotSectionProducesUnknownSourceIssue() {
        StockResearchSnapshot base = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        StockResearchSnapshot snapshot = new StockResearchSnapshot(
                base.security(), null, base.bars(), base.technical(), base.sectors(), base.industryValuation(),
                base.fundFlow(), base.capital(), base.fundamentals(), base.research(), base.news(),
                base.announcements(), base.quality(), base.crossSourceConsistent(), base.coreCompleteness(),
                base.authoritativeSources(), base.fetchedAt());
        OverallReportDraft draft = validDraft(
                "无外部数字的结论", List.of(new OverallSourceReference("quote", "provider", null, null)),
                "仅供学习研究，不构成投资建议");

        OverallReportValidator.Validation result = new OverallReportValidator().validate(draft, snapshot);

        assertThat(result.issues()).contains("UNKNOWN_SOURCE_REFERENCE");
    }

    @Test
    void acceptsLocalDateFromBarsSnapshot() {
        LocalDate date = LocalDate.of(2024, 1, 2);
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"))
                .withBars(DataSection.healthy(
                        List.of(new DailyBar(date, BigDecimal.ONE, BigDecimal.TWO, BigDecimal.ONE,
                                BigDecimal.TWO, BigDecimal.TEN, BigDecimal.TEN)),
                        new Provenance("test-provider", URI.create("https://example.test/bars"),
                                null, Instant.parse("2024-01-02T00:00:00Z"), false, null)));
        OverallReportDraft draft = validDraft(
                "K线日期" + date, List.of(), "仅供学习研究，不构成投资建议");

        OverallReportValidator.Validation result = new OverallReportValidator().validate(draft, snapshot);

        assertThat(result.issues()).doesNotContain("UNSUPPORTED_NUMBER");
    }

    @Test
    void rejectsMissingCoreDataLimitation() {
        StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
        OverallReportDraft draft = new OverallReportDraft(
                "核心行情不可用，无法形成高强度判断", "数据质量不足", "基本面不可用", "技术与资金不可用",
                "估值与行业不可用", "事件与情绪不可用", List.of(), List.of(), List.of(), Map.of(),
                List.of("仅有其他数据缺失"), List.of(), "仅供学习研究，不构成投资建议");

        OverallReportValidator.Validation result = new OverallReportValidator().validate(draft, snapshot);

        assertThat(result.issues()).contains("MISSING_CORE_DATA_LIMITATION");
    }

    @Test
    void reportForcesFixedDisclaimerAndCollectionsAreImmutable() {
        OverallReportDraft draft = validDraft("无外部数字的结论", List.of(), "任意免责声明");
        assertThat(draft.bullishEvidence()).isUnmodifiable();
        assertThat(draft.scenarios()).isUnmodifiable();

        OverallResearchReport report = OverallResearchReport.from(
                draft, "test-model", null, null, "prompt-v1");
        assertThat(report.disclaimer()).isEqualTo("仅供学习研究，不构成投资建议");
        assertThat(report.riskFactors()).isUnmodifiable();
    }

    private static OverallReportDraft validDraft(String conclusion,
            List<OverallSourceReference> sources, String disclaimer) {
        return new OverallReportDraft(
                conclusion,
                "quote 与 bars 均不可用，数据质量不足",
                "基本面数据不可用",
                "技术与资金数据不可用",
                "估值与行业数据不可用",
                "事件与情绪数据不可用",
                List.of(), List.of(), List.of(), Map.of(),
                List.of("quote 不可用", "bars 不可用"), sources, disclaimer);
    }
}
