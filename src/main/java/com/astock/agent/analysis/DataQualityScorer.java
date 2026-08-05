package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.SectionStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * 把快照的时效性、一致性、完整性和来源权威性转换成可解释的质量分解。
 *
 * <p>质量分不是投资置信度，也不会改变确定性判断的固定权重；它只帮助 API、报告和模型
 * 说明当前证据有哪些限制。</p>
 */
public final class DataQualityScorer {

    public DataQualityBreakdown score(StockResearchSnapshot snapshot) {
        // 四个维度独立计分，缺失项通过 notes 暴露原因，不用默认值掩盖数据问题。
        List<String> notes = new ArrayList<>();
        int freshness = healthy(snapshot.quote()) && healthy(snapshot.bars()) ? 30 : 15;
        if (freshness < 30) notes.add("核心行情存在陈旧或不可用分区");

        int consistency = snapshot.crossSourceConsistent() ? 30 : 0;
        if (consistency == 0) notes.add("跨来源一致性未通过或无法验证");

        int completeness = snapshot.coreCompleteness() ? 25 : 10;
        if (completeness < 25) notes.add("核心字段或专业历史长度不完整");

        int authority = snapshot.authoritativeSources() ? 15 : 8;
        if (authority < 15) notes.add("部分数据来自降级来源");

        return new DataQualityBreakdown(
                freshness, consistency, completeness, authority,
                freshness + consistency + completeness + authority, notes);
    }

    private static boolean healthy(com.astock.agent.marketdata.model.DataSection<?> section) {
        return section.status() == SectionStatus.HEALTHY || section.status() == SectionStatus.DEGRADED;
    }
}
