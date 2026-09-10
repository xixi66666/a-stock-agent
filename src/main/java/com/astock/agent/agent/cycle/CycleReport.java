package com.astock.agent.agent.cycle;

import java.util.List;

/** 模型研究结论。市场出处与原书阅读记录由服务端另行附加，不信任模型生成路径。 */
public record CycleReport(String conclusion, List<Dimension> dimensions, List<String> conflicts,
        List<Scenario> scenarios, String calibration, List<String> watchItems, List<String> limitations) {
    public record Dimension(String id, String title, String bookView, List<String> chapterIds,
            List<Fact> facts, String analysis) {}
    public record Fact(String text, List<String> evidenceIds) {}
    public record Scenario(String condition, String interpretation) {}
}
