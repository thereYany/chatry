package com.wherelee.base.modules.platform.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地限流器与本地兜底缓存测试（Redis 不可用时的最后一道保护）。
 *
 * <p>不变式：固定窗口内允许次数严格等于 limit（含并发）；窗口过期后重新计数；
 * 兜底缓存同一 key 在 TTL 内只能被占用一次且并发下只有一个成功者。</p>
 */
class LocalResilienceTest {

    @Test
    void 本地限流达到上限即拒绝且边界精确() {
        LocalRateLimiter limiter = new LocalRateLimiter();
        assertThat(limiter.allow("k1", 2, 60)).isTrue();
        assertThat(limiter.allow("k1", 2, 60)).isTrue();
        // 恰好用完：第 limit+1 次必须拒绝
        assertThat(limiter.allow("k1", 2, 60)).isFalse();
        assertThat(limiter.count("k1", 60)).isEqualTo(3);
    }

    @Test
    void 不同key互不影响() {
        LocalRateLimiter limiter = new LocalRateLimiter();
        assertThat(limiter.allow("a", 1, 60)).isTrue();
        assertThat(limiter.allow("b", 1, 60)).isTrue();
        assertThat(limiter.allow("a", 1, 60)).isFalse();
    }

    @Test
    void 窗口过期后重新计数() throws Exception {
        LocalRateLimiter limiter = new LocalRateLimiter();
        assertThat(limiter.allow("win", 1, 1)).isTrue();
        assertThat(limiter.allow("win", 1, 1)).isFalse();
        Thread.sleep(1100);
        assertThat(limiter.allow("win", 1, 1)).isTrue();
    }

    @Test
    void reset后立即恢复额度() {
        LocalRateLimiter limiter = new LocalRateLimiter();
        assertThat(limiter.allow("r", 1, 60)).isTrue();
        assertThat(limiter.allow("r", 1, 60)).isFalse();
        limiter.reset("r");
        assertThat(limiter.allow("r", 1, 60)).isTrue();
    }

    @Test
    void 并发递增无丢失且允许次数与limit一致() throws Exception {
        LocalRateLimiter limiter = new LocalRateLimiter();
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    if (limiter.allow("concurrent", 10, 60)) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(limiter.count("concurrent", 60)).isEqualTo(threads);
        assertThat(allowed.get()).isEqualTo(10);
    }

    @Test
    void 兜底缓存同一key只允许占用一次_TTL到期后可再次占用() throws Exception {
        LocalFallbackCache cache = new LocalFallbackCache();
        assertThat(cache.reserve("idem:1", Duration.ofMillis(100))).isTrue();
        assertThat(cache.reserve("idem:1", Duration.ofMillis(100))).isFalse();

        cache.release("idem:1");
        assertThat(cache.reserve("idem:1", Duration.ofMillis(100))).isTrue();

        Thread.sleep(150);
        assertThat(cache.reserve("idem:1", Duration.ofMillis(100))).isTrue();
    }

    @Test
    void 兜底缓存并发下只有一个成功者() throws Exception {
        LocalFallbackCache cache = new LocalFallbackCache();
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    if (cache.reserve("same-key", Duration.ofSeconds(60))) {
                        winners.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(winners.get()).isEqualTo(1);
    }
}
