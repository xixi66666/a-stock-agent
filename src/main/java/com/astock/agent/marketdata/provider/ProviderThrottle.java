package com.astock.agent.marketdata.provider;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provider 请求限流器。
 *
 * <p>目前 Eastmoney 使用公平锁全局串行化，并在两次请求完成之间保留最小间隔和随机抖动。
 * 这不是性能优化，而是防止多个并行研究分区把同一来源打成高频请求。</p>
 */
public final class ProviderThrottle {

    private final Duration minimumInterval;
    private final Duration jitter;
    private final ReentrantLock eastmoneyLock = new ReentrantLock(true);
    private long lastEastmoneyCompletionNanos;

    public ProviderThrottle(Duration minimumInterval, Duration jitter) {
        this.minimumInterval = minimumInterval;
        this.jitter = jitter;
    }

    public <T> T call(ProviderId provider, Callable<T> operation) throws Exception {
        // 非 Eastmoney 来源不共享这条特殊串行锁，但仍由 HTTP 客户端统一处理超时和健康状态。
        if (provider != ProviderId.EASTMONEY) {
            return operation.call();
        }

        eastmoneyLock.lockInterruptibly();
        try {
            waitForSlot();
            return operation.call();
        } finally {
            lastEastmoneyCompletionNanos = System.nanoTime();
            eastmoneyLock.unlock();
        }
    }

    private void waitForSlot() throws InterruptedException {
        if (lastEastmoneyCompletionNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - lastEastmoneyCompletionNanos;
        long randomJitter = jitter.isZero()
                ? 0L
                : ThreadLocalRandom.current().nextLong(jitter.toNanos() + 1L);
        long remaining = minimumInterval.toNanos() + randomJitter - elapsed;
        if (remaining > 0L) {
            Thread.sleep(Duration.ofNanos(remaining));
        }
    }
}
