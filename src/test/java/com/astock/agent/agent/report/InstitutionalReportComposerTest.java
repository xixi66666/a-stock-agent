package com.astock.agent.agent.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.analysis.institutional.DeterministicAssessment;
import com.astock.agent.analysis.institutional.ResearchJudgementEngine;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.Provenance;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class InstitutionalReportComposerTest {
    private final InstitutionalReportComposer composer = new InstitutionalReportComposer();

    @Test
    void createsBoundedPackageWithStableEvidenceIds() throws Exception {
        StockResearchSnapshot snapshot = snapshot();
        DeterministicAssessment assessment = new ResearchJudgementEngine().assess(snapshot);
        ReportEvidencePackage result = composer.compose(snapshot, assessment);

        assertThat(result.horizon()).isEqualTo("1-3个月");
        assertThat(result.evidenceCatalog()).isNotEmpty();
        assertThat(result.evidenceCatalog().keySet()).allMatch(id -> id.matches("[a-z0-9-]{3,80}"));
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(result)).doesNotContain("internalScore");
    }

    @Test
    void deterministicFallbackPreservesConflictMissingAndDisclaimer() {
        StockResearchSnapshot snapshot = snapshot();
        DeterministicAssessment assessment = new ResearchJudgementEngine().assess(snapshot);
        InstitutionalResearchReport report = composer.fallback(snapshot, assessment, "model unavailable");

        assertThat(report.generationMode()).isEqualTo(GenerationMode.DETERMINISTIC_FALLBACK);
        assertThat(report.conflicts()).containsExactlyElementsOf(assessment.conflicts());
        assertThat(report.missingData()).containsExactlyElementsOf(assessment.missingData());
        assertThat(report.disclaimer()).isEqualTo("仅供学习研究，不构成投资建议");
    }

    private static StockResearchSnapshot snapshot() {
        SecurityId id = SecurityId.parse("600519");
        Quote quote = new Quote(id, "贵州茅台", bd(100), bd(99), bd(99), bd(101), bd(98), bd(1), bd(1), bd(1000),
                bd(100000), bd(1), bd(2), bd(1), bd(20), bd(20), bd(5), bd(1000000), bd(900000), bd(110), bd(90), Instant.now());
        Provenance source = new Provenance("fixture", URI.create("https://example.com"), null, Instant.now(), false, null);
        return new StockResearchSnapshot(id, DataSection.healthy(quote, source),
                DataSection.healthy(java.util.List.of(new DailyBar(LocalDate.now(), bd(99), bd(101), bd(98), bd(100), bd(1000), bd(100000))), source),
                DataSection.unavailable("technical missing"), DataSection.unavailable("sectors missing"),
                DataSection.unavailable("valuation missing"), DataSection.unavailable("flow missing"),
                DataSection.unavailable("capital missing"), DataSection.unavailable("fundamentals missing"),
                DataSection.unavailable("research missing"), DataSection.unavailable("news missing"),
                DataSection.unavailable("announcements missing"), null, false, false, false, Instant.now());
    }

    private static BigDecimal bd(double value) { return BigDecimal.valueOf(value); }
}
