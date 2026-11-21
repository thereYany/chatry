package com.wherelee.base.modules.platform.aop;

import com.wherelee.base.modules.platform.common.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedLockAspectTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOperations;
    private DistributedLockAspect aspect;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOperations);
        aspect = new DistributedLockAspect(redis, new SimpleMeterRegistry());
    }

    @AfterEach
    void clear() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void acquiresLockRunsMethodAndReleasesWithTokenCheck() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        Target proxy = proxy(new Target());
        assertThat(proxy.work("a")).isEqualTo("ok:a");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(key.capture(), anyString(), any(Duration.class));
        assertThat(key.getValue()).startsWith("atry:lock:test:");
        verify(redis).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    void failsFastWhenLockHeldAndWaitIsZero() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        Target proxy = proxy(new Target());
        assertThatThrownBy(() -> proxy.work("a"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.status().value()).isEqualTo(409));
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    void redisFailureIsFailClosed() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        Target proxy = proxy(new Target());
        assertThatThrownBy(() -> proxy.work("a"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.status().value()).isEqualTo(503));
    }

    @Test
    void spelExpressionBuildsKeyFromArgument() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        Target proxy = proxy(new Target());
        proxy.byOrder("M123");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(key.capture(), anyString(), any(Duration.class));
        assertThat(key.getValue()).isEqualTo("atry:lock:order:M123");
    }

    @Test
    void methodFailureStillReleasesLock() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        Target proxy = proxy(new Target());
        assertThatThrownBy(() -> proxy.boom()).isInstanceOf(IllegalStateException.class);
        verify(redis).execute(any(RedisScript.class), anyList(), anyString());
    }

    private Target proxy(Target target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    static class Target {

        @DistributedLock(key = "test", waitMs = 0, leaseMs = 5_000)
        public String work(String value) {
            return "ok:" + value;
        }

        @DistributedLock(key = "order", keyExpression = "#orderNo")
        public String byOrder(String orderNo) {
            return orderNo;
        }

        @DistributedLock(key = "boom", leaseMs = 5_000)
        public String boom() {
            throw new IllegalStateException("boom");
        }
    }
}
