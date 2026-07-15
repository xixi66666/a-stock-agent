package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.SectionStatus;
import java.util.ArrayList;
import java.util.List;

public final class DataQualityScorer {

    public DataQualityBreakdown score(StockResearchSnapshot snapshot) {
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
