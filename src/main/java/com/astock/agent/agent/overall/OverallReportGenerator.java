package com.astock.agent.agent.overall;

import com.astock.agent.analysis.StockResearchSnapshot;
import java.util.List;

/** 总体研究报告生成器的可替换边界，便于离线测试和切换模型。 */
public interface OverallReportGenerator {

    OverallReportDraft generate(StockResearchSnapshot snapshot) throws Exception;

    /** 生成器可以接收补充证据；旧实现默认忽略它，保持现有离线实现兼容。 */
    default OverallReportDraft generate(StockResearchSnapshot snapshot,
            SupplementalResearchEvidence evidence) throws Exception {
        return generate(snapshot);
    }

    /** 校验失败时最多由编排服务调用一次；默认实现保持原草稿，避免隐式二次调用。 */
    default OverallReportDraft repair(StockResearchSnapshot snapshot,
            OverallReportDraft draft, List<String> issues) throws Exception {
        return draft;
    }

    default OverallReportDraft repair(StockResearchSnapshot snapshot,
            OverallReportDraft draft, List<String> issues,
            SupplementalResearchEvidence evidence) throws Exception {
        return repair(snapshot, draft, issues);
    }

    default String modelName() {
        return "configured-overall-model";
    }
}
