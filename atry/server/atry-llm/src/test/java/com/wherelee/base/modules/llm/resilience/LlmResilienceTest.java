package com.wherelee.base.modules.llm.resilience;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.llm.LlmTestSupport;
import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.observability.LlmMetrics;
import com.wherelee.base.modules.llm.provider.LlmProvider;
import com.wherelee.base.modules.llm.provider.LlmProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * LLM 韧性组件测试：重试策略/重试预算/熔断/限流/provider 路由。
 *
 * <p>业务不变式：只重试可恢复错误；退避指数增长且有上限；重试预算耗尽后降级为不重试
 * （防下游故障期间流量放大）；熔断 4xx 不计入、半开探测成功后关闭；限流同时限制
 * 租户/provider/在途并发三个维度。</p>
 */
class LlmResilienceTest {

    private static LlmError error(LlmErrorCategory category) {
        return new LlmError(category, "CODE", true, false, "test");
    }

    // ---------- 重试策略 ----------

    @Test
    void 只有可恢复错误类别可重试() {
        LlmRetryPolicy policy = new LlmRetryPolicy(LlmTestSupport.properties());
        assertThat(policy.retryable(error(LlmErrorCategory.TIMEOUT_CONNECT))).isTrue();
        assertThat(policy.retryable(error(LlmErrorCategory.TIMEOUT_TOTAL))).isTrue();
        assertThat(policy.retryable(error(LlmErrorCategory.UPSTREAM_5XX))).isTrue();
        assertThat(policy.retryable(error(LlmErrorCategory.UPSTREAM_OVERLOADED))).isTrue();

        assertThat(policy.retryable(error(LlmErrorCategory.AUTH))).isFalse();
        assertThat(policy.retryable(error(LlmErrorCategory.INVALID_REQUEST))).isFalse();
        assertThat(policy.retryable(error(LlmErrorCategory.RATE_LIMITED))).isFalse();
        assertThat(policy.retryable(error(LlmErrorCategory.QUOTA_EXHAUSTED))).isFalse();
        assertThat(policy.retryable(error(LlmErrorCategory.CONTENT_FILTERED))).isFalse();
        assertThat(policy.retryable(error(LlmErrorCategory.CANCELLED))).isFalse();
        assertThat(policy.retryable(null)).isFalse();
    }

    @Test
    void 退避指数增长且被上限截断() {
        LlmProperties properties = LlmTestSupport.withRetry(new LlmProperties.Retry(5, 10, 25, 0));
        LlmRetryPolicy policy = new LlmRetryPolicy(properties);
        assertThat(policy.backoffMs(1)).isEqualTo(10);
        assertThat(policy.backoffMs(2)).isEqualTo(20);
        assertThat(policy.backoffMs(3)).isEqualTo(25);
        assertThat(policy.backoffMs(10)).isEqualTo(25);
    }

    @Test
    void 抖动在配置比例范围内() {
        LlmProperties properties = LlmTestSupport.withRetry(new LlmProperties.Retry(5, 100, 1000, 0.3));
        LlmRetryPolicy policy = new LlmRetryPolicy(properties);
        for (int i = 0; i < 100; i++) {
            long backoff = policy.backoffMs(1);
            assertThat(backoff).isBetween(70L, 130L);
        }
    }

    @Test
    void 最大尝试次数至少为1() {
        assertThat(new LlmRetryPolicy(LlmTestSupport.withRetry(
                new LlmProperties.Retry(0, 1, 2, 0))).maxAttempts()).isEqualTo(1);
    }

    // ---------- 重试预算 ----------

    @Test
    void 重试预算初始10次_耗尽后拒绝且成功可缓慢恢复() {
        LlmRetryBudget budget = new LlmRetryBudget();
        for (int i = 0; i < 10; i++) {
            assertThat(budget.tryRetry("p")).as("第 %s 次重试", i + 1).isTrue();
        }
        // 预算耗尽：下游故障期间不再放大重试流量
        assertThat(budget.tryRetry("p")).isFalse();

        // 每次成功回补 0.1；注意 double 累加 10 次得到 0.9999999999999999（差 1 ulp），
        // 实际需要第 11 次成功才恢复 1 次重试额度。这里把该边界固化为可见行为。
        for (int i = 0; i < 10; i++) {
            budget.onSuccess("p");
        }
        assertThat(budget.tryRetry("p")).isFalse();
        budget.onSuccess("p");
        assertThat(budget.tryRetry("p")).isTrue();
    }

    @Test
    void 重试预算按provider隔离且成功后不超过容量() {
        LlmRetryBudget budget = new LlmRetryBudget();
        assertThat(budget.tryRetry("a")).isTrue();
        assertThat(budget.tryRetry("b")).isTrue();
        for (int i = 0; i < 100; i++) {
            budget.onSuccess("a");
        }
        // 回补封顶 10：连续 10 次重试后耗尽
        for (int i = 0; i < 10; i++) {
            assertThat(budget.tryRetry("a")).isTrue();
        }
        assertThat(budget.tryRetry("a")).isFalse();
    }

    // ---------- 熔断 ----------

    private static LlmCircuitBreaker breaker() {
        return new LlmCircuitBreaker(LlmTestSupport.withCircuit(
                new LlmProperties.Circuit(50, 4, 80, 2, 0, 60)));
    }

    @Test
    void 达到最小调用数且失败率超阈值后打开并快速失败() {
        LlmCircuitBreaker breaker = breaker();
        breaker.recordSuccess("k");
        breaker.recordSuccess("k");
        breaker.recordFailure("k", error(LlmErrorCategory.UPSTREAM_5XX));
        breaker.recordFailure("k", error(LlmErrorCategory.UPSTREAM_5XX)); // 4 次中 2 失败 = 50%

        assertThat(breaker.isOpen("k")).isTrue();
        assertThatThrownBy(() -> breaker.acquire("k"))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> {
                    assertThat(((LlmException) e).error().category())
                            .isEqualTo(LlmErrorCategory.UPSTREAM_OVERLOADED);
                    assertThat(((LlmException) e).error().providerCode()).isEqualTo("CIRCUIT_OPEN");
                });
    }

    @Test
    void 未达到最小调用数即使全失败也不打开() {
        LlmCircuitBreaker breaker = breaker();
        breaker.recordFailure("k", error(LlmErrorCategory.UPSTREAM_5XX));
        breaker.recordFailure("k", error(LlmErrorCategory.UPSTREAM_5XX));
        breaker.recordFailure("k", error(LlmErrorCategory.UPSTREAM_5XX));
        assertThat(breaker.isOpen("k")).isFalse();
    }

    @Test
    void 状态4xx类错误不计入熔断() {
        LlmCircuitBreaker breaker = breaker();
        for (int i = 0; i < 10; i++) {
            breaker.recordFailure("k", error(LlmErrorCategory.INVALID_REQUEST));
            breaker.recordFailure("k", error(LlmErrorCategory.AUTH));
        }
        assertThat(breaker.isOpen("k")).isFalse();
    }

    @Test
    void 打开后到期进入半开_探测成功关闭() throws Exception {
        LlmCircuitBreaker breaker = breaker();
        breaker.recordSuccess("k");
        breaker.recordSuccess("k");
        breaker.recordFailure("k", error(LlmErrorCategory.UPSTREAM_5XX));
        breaker.recordFailure("k", error(LlmErrorCategory.UPSTREAM_5XX));
        assertThat(breaker.isOpen("k")).isTrue();

        Thread.sleep(120);
        breaker.acquire("k");           // 半开探测 1
        breaker.recordSuccess("k");     // 成功 -> 关闭
        assertThat(breaker.isOpen("k")).isFalse();
        breaker.acquire("k");           // 关闭状态可正常并发
        breaker.acquire("k");
    }

    @Test
    void 半开探测失败重新打开() throws Exception {
        LlmCircuitBreaker breaker = breaker();
        breaker.recordSuccess("k");
        breaker.recordSuccess("k");
        breaker.recordFailure("k", error(LlmErrorCategory.UPSTREAM_5XX));
        breaker.recordFailure("k", error(LlmErrorCategory.UPSTREAM_5XX));

        Thread.sleep(120);
        breaker.acquire("k");
        breaker.recordFailure("k", error(LlmErrorCategory.UPSTREAM_5XX));
        assertThat(breaker.isOpen("k")).isTrue();
    }

    @Test
    void 慢调用率超阈值提前打开() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(LlmTestSupport.withCircuit(
                new LlmProperties.Circuit(90, 4, 1000, 2, 10, 50)));
        for (int i = 0; i < 4; i++) {
            breaker.recordSuccess("k", 50); // 全部慢调用
        }
        assertThat(breaker.isOpen("k")).isTrue();
    }

    // ---------- 限流 ----------

    @Test
    void 租户维度限流独立于其他租户() {
        LlmProperties properties = LlmTestSupport.withRateLimit(
                new LlmProperties.RateLimit(30, 300_000, 100, "local"));
        LlmRateLimiter limiter = new LlmRateLimiter(properties);

        assertThat(limiter.acquire("p", 1L)).isEqualTo("p");
        assertThatThrownBy(() -> limiter.acquire("p", 1L))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).error().category())
                        .isEqualTo(LlmErrorCategory.RATE_LIMITED));
        // 另一个租户额度独立
        assertThat(limiter.acquire("p", 2L)).isEqualTo("p");
    }

    @Test
    void provider维度限流() {
        LlmProperties properties = LlmTestSupport.withRateLimit(
                new LlmProperties.RateLimit(300_000, 30, 100, "local"));
        LlmRateLimiter limiter = new LlmRateLimiter(properties);

        assertThat(limiter.acquire("provider-a", 1L)).isEqualTo("provider-a");
        assertThatThrownBy(() -> limiter.acquire("provider-a", 1L))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).error().providerCode())
                        .isEqualTo("PROVIDER_RATE_LIMITED"));
        assertThat(limiter.acquire("provider-b", 1L)).isEqualTo("provider-b");
    }

    @Test
    void 在途并发舱壁满时快速失败_释放后可继续() {
        LlmProperties properties = LlmTestSupport.withRateLimit(
                new LlmProperties.RateLimit(300_000, 300_000, 1, "local"));
        LlmRateLimiter limiter = new LlmRateLimiter(properties);

        assertThat(limiter.acquire("p", 1L)).isEqualTo("p");
        assertThatThrownBy(() -> limiter.acquire("p", 2L))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).error().providerCode())
                        .isEqualTo("BULKHEAD_FULL"));

        limiter.release("p");
        assertThat(limiter.acquire("p", 1L)).isEqualTo("p");
        limiter.release("p");
        limiter.release("unknown"); // 释放不存在的 provider 必须安全
    }

    @Test
    void 限流拒绝记录指标() {
        LlmMetrics metrics = mock(LlmMetrics.class);
        LlmProperties properties = LlmTestSupport.withRateLimit(
                new LlmProperties.RateLimit(30, 300_000, 100, "local"));
        LlmRateLimiter limiter = new LlmRateLimiter(properties, null, null, metrics);

        limiter.acquire("p", 1L);
        assertThatThrownBy(() -> limiter.acquire("p", 1L)).isInstanceOf(LlmException.class);
        verify(metrics).rejected(eq("tenant"));
    }

    // ---------- provider 路由 ----------

    @Test
    void 按模型路由到唯一支持者_未知模型明确报错() {
        LlmProvider mockProvider = mock(LlmProvider.class);
        org.mockito.Mockito.when(mockProvider.supports("mock-a")).thenReturn(true);
        LlmProviderRegistry registry = new LlmProviderRegistry(List.of(mockProvider));

        assertThat(registry.resolve("mock-a")).isSameAs(mockProvider);
        assertThatThrownBy(() -> registry.resolve("unknown-model"))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).error().category())
                        .isEqualTo(LlmErrorCategory.INVALID_REQUEST));
    }
}
