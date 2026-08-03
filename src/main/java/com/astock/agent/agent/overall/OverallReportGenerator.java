package com.astock.agent.agent.overall;

import com.astock.agent.analysis.StockResearchSnapshot;
import java.util.List;

/** 总体研究报告生成器的可替换边界，便于离线测试和切换模型。 */
public interface OverallReportGenerator {

    OverallReportDraft generate(StockResearchSnapshot snapshot) throws Exception;

    /** 校验失败时最多由编排服务调用一次；默认实现保持原草稿，避免隐式二次调用。 */
    default OverallReportDraft repair(StockResearchSnapshot snapshot,
            OverallReportDraft draft, List<String> issues) throws Exception {
        return draft;
    }

    default String modelName() {
        return "configured-overall-model";
    }
}
