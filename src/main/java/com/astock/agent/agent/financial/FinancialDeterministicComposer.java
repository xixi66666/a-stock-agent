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
            tierText = "总体判断：财务质量为“" + score.tier() + "”。F-Score 为 "
                    + score.total() + " 分（满分 9 分），说明盈利、现金流、杠杆与经营效率的综合表现"
                    + tierMeaning(score.tier()) + "。";
        } else {
            tierText = "总体判断：数据不足。报告期不足 4 期，暂时无法形成可靠的财务质量评分。";
        }
        return new FinancialNarrative(tierText, signalText(pack), trendText(pack), riskText(pack));
    }

    private String signalText(FinancialEvidencePackage pack) {
        List<FinancialQualityScore.SignalResult> signals = pack.qualityScore().signals();
        if (signals.isEmpty()) {
            return "当前没有可评估的财务质量信号。";
        }
        List<String> sections = new ArrayList<>();
        appendSignalGroup(sections, signals, FinancialQualityScore.SignalStatus.PASS,
                "表现较好");
        appendSignalGroup(sections, signals, FinancialQualityScore.SignalStatus.FAIL,
                "需要关注");
        appendSignalGroup(sections, signals, FinancialQualityScore.SignalStatus.UNVERIFIED,
                "暂时无法判断");
        return String.join("。", sections) + "。详细数值与判定依据见下方信号明细。";
    }

    private String trendText(FinancialEvidencePackage pack) {
        if (pack.trends().series().isEmpty()) {
            return "当前没有可展示的趋势序列。";
        }
        List<String> sections = new ArrayList<>();
        appendTrendGroup(sections, pack, "RISING", "上升");
        appendTrendGroup(sections, pack, "FALLING", "下降");
        appendTrendGroup(sections, pack, "MIXED", "波动");
        appendTrendGroup(sections, pack, "INSUFFICIENT", "样本不足");
        return String.join("；", sections) + "。关键同比变化见上方趋势摘要。";
    }

    private String riskText(FinancialEvidencePackage pack) {
        List<String> risks = new ArrayList<>();
        if (pack.unverifiedCount() > 0) {
            risks.add(pack.unverifiedCount() + " 个信号因字段或历史期数不足无法评估，评分结论受数据完整性限制。");
        }
        if (pack.financialIndustry()) {
            risks.add("该公司属于金融行业，毛利率与资产周转率信号不适用传统口径。");
        }
        risks.add("口径说明：利润表和现金流量表是年初至今累计值，同比表示本期与上年同期比较，不代表单季度变化。");
        return String.join(" ", risks);
    }

    private static void appendSignalGroup(List<String> output,
            List<FinancialQualityScore.SignalResult> signals,
            FinancialQualityScore.SignalStatus status,
            String label) {
        List<String> names = signals.stream()
                .filter(signal -> signal.status() == status)
                .map(FinancialQualityScore.SignalResult::name)
                .toList();
        if (!names.isEmpty()) {
            output.add(label + "：" + String.join("、", names));
        }
    }

    private static void appendTrendGroup(List<String> output,
            FinancialEvidencePackage pack, String direction, String label) {
        List<String> names = pack.trends().series().stream()
                .filter(series -> direction.equals(series.direction())
                        || ("INSUFFICIENT".equals(direction)
                        && !List.of("RISING", "FALLING", "MIXED").contains(series.direction())))
                .map(com.astock.agent.analysis.financial.FinancialTrendResult.TrendSeries::name)
                .toList();
        if (!names.isEmpty()) {
            output.add(label + "：" + String.join("、", names));
        }
    }

    private static String tierMeaning(String tier) {
        return switch (tier) {
            case "优" -> "较强";
            case "良" -> "较稳健，但仍有需要关注的项目";
            case "中" -> "一般，优势与风险并存";
            case "弱" -> "偏弱，需要重点核查未通过项目";
            default -> "尚不明确";
        };
    }
}
