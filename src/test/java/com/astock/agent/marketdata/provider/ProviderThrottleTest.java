package com.astock.agent.marketdata.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderThrottleTest {

    @Test
    void eastmoneyRequestsNeverOverlap() throws Exception {
        ProviderThrottle throttle = new ProviderThrottle(Duration.ofMillis(20), Duration.ZERO);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        runConcurrently(4, () -> throttle.call(ProviderId.EASTMONEY, () -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            Thread.sleep(10);
            active.decrementAndGet();
            return true;
        }));

        assertThat(maximum).hasValue(1);
    }

    @Test
    void providersWithoutStrictPolicyMayRunConcurrently() throws Exception {
        ProviderThrottle throttle = new ProviderThrottle(Duration.ZERO, Duration.ZERO);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        runConcurrently(4, () -> throttle.call(ProviderId.TENCENT, () -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            Thread.sleep(20);
            active.decrementAndGet();
            return true;
        }));

        assertThat(maximum.get()).isGreaterThan(1);
    }

    private static void runConcurrently(int count, ThrowingRunnable operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    operation.run();
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
