package com.astock.agent.marketdata.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * 一条规范化数据的来源元数据。
 *
 * <p>{@code providerTimestamp} 表示供应商数据本身的时间，{@code fetchedAt} 表示本次抓取时间；
 * 两者不能混为“最新时间”。缓存和备用来源也必须显式保留，方便报告和 UI 解释可信边界。</p>
 */
public record Provenance(
        String provider,
        URI sourceUrl,
        Instant providerTimestamp,
        Instant fetchedAt,
        boolean cached,
        String fallbackProvider) {

    public Provenance {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
    }
}
