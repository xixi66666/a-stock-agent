package com.astock.agent.marketdata.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 记录 Provider 成功、阻断和冷却窗口。
 *
 * <p>当供应商返回 403、429 或连续失败时，客户端可以把它放入冷却期；后续请求应尽快
 * 选择备用来源或返回局部不可用，而不是在冷却期间继续重试。</p>
 */
public final class ProviderHealthRegistry {

    private final Duration cooldown;
    private final Clock clock;
    private final Map<ProviderId, Instant> blockedUntil = new EnumMap<>(ProviderId.class);
    private final Map<ProviderId, Instant> lastSuccess = new EnumMap<>(ProviderId.class);

    public ProviderHealthRegistry(Duration cooldown, Clock clock) {
        this.cooldown = cooldown;
        this.clock = clock;
    }

    public synchronized void recordBlocked(ProviderId provider, Instant blockedAt) {
        blockedUntil.put(provider, blockedAt.plus(cooldown));
    }

    public synchronized void recordSuccess(ProviderId provider, Instant succeededAt) {
        lastSuccess.put(provider, succeededAt);
        blockedUntil.remove(provider);
    }

    public synchronized ProviderAvailability availability(ProviderId provider) {
        // 过期冷却在读取时清理；返回值同时保留上次成功时间，方便诊断数据新鲜度。
        Instant now = clock.instant();
        Instant until = blockedUntil.get(provider);
        if (until != null && now.isBefore(until)) {
            return new ProviderAvailability(
                    ProviderAvailabilityStatus.COOLDOWN,
                    false,
                    Optional.ofNullable(lastSuccess.get(provider)),
                    Optional.of(until));
        }
        if (until != null) {
            blockedUntil.remove(provider);
        }
        return new ProviderAvailability(
                ProviderAvailabilityStatus.AVAILABLE,
                true,
                Optional.ofNullable(lastSuccess.get(provider)),
                Optional.empty());
    }
}
