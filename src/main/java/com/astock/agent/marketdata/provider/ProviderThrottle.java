package com.astock.agent.marketdata.provider;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

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
