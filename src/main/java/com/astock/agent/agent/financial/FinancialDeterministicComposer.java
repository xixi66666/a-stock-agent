package com.astock.agent.agent.financial;

import com.astock.agent.analysis.financial.FinancialQualityScore;
import java.util.ArrayList;
import java.util.List;

/** 模型不可用或校验失败时的确定性文字组装:全部文字来自证据包。 */
public final class FinancialDeterministicComposer {

    public static final String REQUIRED_DISCLAIMER = "仅供学习研究，不构成投资建议";

    public FinancialNarrative compose(FinancialEvidencePackage pack) {
        FinancialQualityScore score = pack.qualityScore();
        String tierText;
        if (score.sufficientData()) {
            tierText = "按确定性规则计算，" + pack.securityCode()
                    + " 财务质量 F-Score 为 " + score.total() + " 分，档位 " + score.tier()
                    + "（0-2 弱 / 3-5 中 / 6-7 良 / 8-9 优），共评估 "
                    + score.evaluatedSignals() + " 个信号。";
        } else {
            tierText = "报告期不足 4 期，无法计算财务质量评分，当前状态为数据不足。";
        }
        return new FinancialNarrative(tierText, signalText(pack), trendText(pack), riskText(pack));
    }

    private String signalText(FinancialEvidencePackage pack) {
        List<String> lines = pack.qualityScore().signals().stream()
                .map(signal -> signal.number() + " " + signal.name() + "："
                        + statusText(signal.status()) + "。" + signal.evidence())
                .toList();
        return lines.isEmpty() ? "当前没有可评估的财务质量信号。" : String.join(" ", lines);
    }

    private String trendText(FinancialEvidencePackage pack) {
        List<String> lines = pack.trends().series().stream()
                .map(series -> series.name() + " 方向为 " + directionText(series.direction()))
                .toList();
        return lines.isEmpty() ? "当前没有可展示的趋势序列。" : String.join(" ", lines);
    }

    private String riskText(FinancialEvidencePackage pack) {
        List<String> risks = new ArrayList<>();
        if (pack.unverifiedCount() > 0) {
            risks.add(pack.unverifiedCount() + " 个信号因字段或历史期数不足无法评估，评分结论受数据完整性限制。");
        }
        if (pack.financialIndustry()) {
            risks.add("该公司属于金融行业，毛利率与资产周转率信号不适用传统口径。");
        }
        risks.add("趋势基于报告期累计口径，同比为当期与上年同期比较。");
        return String.join(" ", risks);
    }

    private static String statusText(FinancialQualityScore.SignalStatus status) {
        return switch (status) {
            case PASS -> "通过";
            case FAIL -> "未通过";
            case UNVERIFIED -> "无法评估";
        };
    }

    private static String directionText(String direction) {
        return switch (direction) {
            case "RISING" -> "上升";
            case "FALLING" -> "下降";
            case "MIXED" -> "波动";
            default -> "样本不足";
        };
    }
}
