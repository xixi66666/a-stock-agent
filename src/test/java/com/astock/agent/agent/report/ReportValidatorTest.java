package com.astock.agent.agent.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.institutional.Direction;
import com.astock.agent.analysis.institutional.EvidenceStatus;
import com.astock.agent.analysis.institutional.ReportEvidence;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportValidatorTest {
    @Test
    void rejectsUnknownEvidenceInventedNumberAndTradeInstruction() {
        ReportEvidencePackage evidence = evidencePackage();
        ReportNarrativeDraft draft = new ReportNarrativeDraft(
                "建议买入，目标价30% [unknown-id]，预计利润为999亿元", "趋势解释", "基本面解释", "估值解释", List.of(), List.of());

        ReportValidator.ValidationResult result = new ReportValidator().validate(draft, evidence);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).contains("UNKNOWN_EVIDENCE", "UNSUPPORTED_NUMBER", "TRADE_INSTRUCTION");
    }

    private static ReportEvidencePackage evidencePackage() {
        ReportEvidence item = new ReportEvidence("quote-price", "价格", "当前价格20元", "quote", "fixture", Instant.now());
        return new ReportEvidencePackage("600519", "贵州茅台", "1-3个月", Direction.NEUTRAL,
                EvidenceStatus.PARTIAL, Map.of(item.id(), item), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Instant.now(), "v1");
    }
}
