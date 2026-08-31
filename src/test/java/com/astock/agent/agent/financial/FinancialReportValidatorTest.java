package com.astock.agent.agent.financial;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.analysis.financial.FinancialQualityScore;
import com.astock.agent.analysis.financial.FinancialTrendResult;
import com.astock.agent.marketdata.model.Exchange;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SecurityId;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialReportValidatorTest {

    private final FinancialReportValidator validator = new FinancialReportValidator();
    private final FinancialEvidencePackage pack = new FinancialEvidencePackage(
            "600519",
            new FinancialStatementHistory(new SecurityId("600519", Exchange.SHANGHAI), List.of()),
            new FinancialQualityScore(6, "良", 9, List.of(), true),
            new FinancialTrendResult(List.of(), 12),
            false, 6, 3, 0, 9);

    @Test
    void validDraftPasses() {
        FinancialReportValidator.Validation validation = validator.validate(
                new FinancialNarrativeDraft(
                        "该证券财务质量 F-Score 为 6 分，档位 良，评估 9 个信号。",
                        "盈利与现金流信号通过；毛利率与周转率信号未通过。",
                        "营业总收入同比持续上升，趋势方向为上升。",
                        "需注意样本期内数据完整性与行业口径限制。",
                        FinancialDeterministicComposer.REQUIRED_DISCLAIMER),
                pack);

        assertThat(validation.blocking()).isFalse();
    }

    @Test
    void fabricatedNumberIsRejected() {
        FinancialReportValidator.Validation validation = validator.validate(
                new FinancialNarrativeDraft(
                        "F-Score 为 6 分，档位 良。",
                        "净利润 999 亿元。",
                        "营收稳定。",
                        "无风险。",
                        FinancialDeterministicComposer.REQUIRED_DISCLAIMER),
                pack);

        assertThat(validation.issues()).contains("UNSUPPORTED_NUMBER");
    }

    @Test
    void missingTierReferenceIsRejected() {
        FinancialReportValidator.Validation validation = validator.validate(
                new FinancialNarrativeDraft(
                        "F-Score 为 6 分。",
                        "信号正常。",
                        "趋势正常。",
                        "无风险。",
                        FinancialDeterministicComposer.REQUIRED_DISCLAIMER),
                pack);

        assertThat(validation.issues()).contains("MISSING_TIER_REFERENCE");
    }

    @Test
    void tradeInstructionAndWrongDisclaimerAreRejected() {
        FinancialReportValidator.Validation validation = validator.validate(
                new FinancialNarrativeDraft(
                        "F-Score 为 6 分，档位 良，建议买入。",
                        "信号正常。",
                        "趋势正常。",
                        "无风险。",
                        "随便写的免责声明"),
                pack);

        assertThat(validation.issues()).contains("TRADE_INSTRUCTION", "INVALID_DISCLAIMER");
    }

    @Test
    void insufficientDataRequiresInsufficientMention() {
        FinancialEvidencePackage insufficient = new FinancialEvidencePackage(
                "600519", pack.history(),
                new FinancialQualityScore(0, "数据不足", 0, List.of(), false),
                new FinancialTrendResult(List.of(), 3), false, 0, 0, 0, 0);
        FinancialReportValidator.Validation validation = validator.validate(
                new FinancialNarrativeDraft("档位 良。", "信号正常。", "趋势正常。", "无风险。",
                        FinancialDeterministicComposer.REQUIRED_DISCLAIMER),
                insufficient);

        assertThat(validation.issues()).contains("MISSING_TIER_REFERENCE");
    }
}
