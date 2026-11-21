package com.wherelee.base.modules.platform.aop;

import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.resilience.LocalFallbackCache;
import com.wherelee.base.modules.platform.resilience.RedisFallbackMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 幂等切面测试。
 *
 * <p>业务不变式：同一 key + 同一入参在窗口内只允许成功执行一次；执行抛异常必须释放幂等键，
 * 允许调用方重试；Redis 故障时降级到本地幂等缓存而不是放行重复请求。</p>
 */
class IdempotentAspectTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private LocalFallbackCache fallbackCache;
    private AtomicInteger executions;
    private Target proxy;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        fallbackCache = new LocalFallbackCache();
        executions = new AtomicInteger();
        IdempotentAspect aspect = new IdempotentAspect(redis, fallbackCache,
                new RedisFallbackMetrics(new SimpleMeterRegistry()));
        AspectJProxyFactory factory = new AspectJProxyFactory(new Target(executions));
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    @Test
    void 首次调用执行_重复调用返回409且不执行目标方法() {
        assertThat(proxy.work("a")).isEqualTo("ok-a");
        assertThatThrownBy(() -> proxy.work("a"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重复请求");
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void 不同入参互不影响() {
        assertThat(proxy.work("a")).isEqualTo("ok-a");
        assertThat(proxy.work("b")).isEqualTo("ok-b");
        assertThat(executions.get()).isEqualTo(2);
    }

    @Test
    void 目标方法异常时释放幂等键允许重试() {
        assertThatThrownBy(() -> proxy.work("fail")).isInstanceOf(IllegalStateException.class);
        // 失败后 key 必须释放，同一请求可以重试
        assertThat(proxy.work("fail")).isEqualTo("ok-fail");
        assertThat(executions.get()).isEqualTo(2);
        verify(redis).delete(anyString());
    }

    @Test
    void Redis判定重复时不执行目标方法() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThatThrownBy(() -> proxy.work("a"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重复请求");
        assertThat(executions.get()).isZero();
    }

    @Test
    void Redis故障时降级本地幂等缓存仍能拦截重复() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));
        assertThat(proxy.work("a")).isEqualTo("ok-a");
        assertThatThrownBy(() -> proxy.work("a")).isInstanceOf(BizException.class);
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void Redis故障且目标方法失败时本地键被释放() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));
        assertThatThrownBy(() -> proxy.work("fail")).isInstanceOf(IllegalStateException.class);
        assertThat(proxy.work("fail")).isEqualTo("ok-fail");
        verify(redis, never()).delete(anyString());
    }

    /** 被切目标：入参 "fail" 时抛异常。 */
    public static class Target {
        private final AtomicInteger executions;

        public Target(AtomicInteger executions) {
            this.executions = executions;
        }

        @Idempotent(key = "unit-test", windowSeconds = 60)
        public String work(String arg) {
            int attempt = executions.incrementAndGet();
            // 只让第一次失败：验证失败释放幂等键后可以重试成功
            if ("fail".equals(arg) && attempt == 1) {
                throw new IllegalStateException("模拟业务失败");
            }
            return "ok-" + arg;
        }
    }
}
