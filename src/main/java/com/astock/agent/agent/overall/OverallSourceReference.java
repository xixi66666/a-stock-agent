package com.astock.agent.agent.overall;

import java.time.Instant;

/** 总体报告引用的规范化数据分区来源。 */
public record OverallSourceReference(
        String section,
        String provider,
        String sourceUrl,
        Instant fetchedAt) {
}
