package com.wherelee.base.modules.identity.security;

import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.config.SecurityProperties;
import com.wherelee.base.modules.platform.resilience.LocalRateLimiter;
import com.wherelee.base.modules.platform.resilience.RedisFallbackMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录节流与公开接口节流测试。
 *
 * <p>业务不变式：用户维度与 IP 维度双窗口计数；达到阈值（>=）即 429；
 * 登录成功清零；Redis 故障时降级本地计数；关闭保护时完全跳过。</p>
 */
class LoginThrottleTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private LocalRateLimiter localRateLimiter;
    private SimpleMeterRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        localRateLimiter = new LocalRateLimiter();
        registry = new SimpleMeterRegistry();
    }

    private LoginThrottleService service(SecurityProperties properties) {
        return new LoginThrottleService(redis, properties, registry, localRateLimiter,
                new RedisFallbackMetrics(registry));
    }

    private static SecurityProperties properties(boolean enabled, int userMax, int ipMax) {
        return new SecurityProperties(List.of(), false, enabled, userMax, ipMax, 10);
    }

    @Test
    void 保护关闭时不做任何检查() {
        LoginThrottleService service = service(properties(false, 1, 1));
        // Redis 中已有大量失败也不应拦截
        when(ops.get(anyString())).thenReturn("999");
        service.check("alice", "1.1.1.1");
        service.recordFailure("alice", "1.1.1.1");
        service.clear("alice", "1.1.1.1");
        Mockito.verifyNoInteractions(redis);
    }

    @Test
    void 用户维度计数达到阈值时拒绝_边界精确() {
        LoginThrottleService service = service(properties(true, 3, 100));
        when(ops.get(anyString())).thenReturn("2");
        service.check("alice", "1.1.1.1"); // 2 < 3 放行

        when(ops.get(anyString())).thenReturn("3");
        assertThatThrownBy(() -> service.check("alice", "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("登录尝试过于频繁");
        // 被拦截必须计入指标
        assertThat(registry.get("security.login.blocked").counter().count()).isEqualTo(1);
    }

    @Test
    void IP维度超限时即使用户名不同也拒绝() {
        LoginThrottleService service = service(properties(true, 100, 2));
        when(ops.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return key.startsWith("atry:login:ip:") ? "2" : "0";
        });
        assertThatThrownBy(() -> service.check("bob", "9.9.9.9"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void Redis故障时降级本地计数并生效() {
        LoginThrottleService service = service(properties(true, 2, 100));
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        // 本地计数先到 2（阈值），Redis 不可用时必须以本地计数为准
        localRateLimiter.increment(service.key("alice", "1.1.1.1"), 600);
        localRateLimiter.increment(service.key("alice", "1.1.1.1"), 600);

        assertThatThrownBy(() -> service.check("alice", "1.1.1.1"))
                .isInstanceOf(BizException.class);
        // 本地计数被读取；降级必须记录指标
        assertThat(registry.get("infra.redis.fallback")
                .tag("component", "login_throttle").counters()).isNotEmpty();
    }

    @Test
    void 记录失败同时递增用户与IP计数() {
        LoginThrottleService service = service(properties(true, 5, 10));
        when(ops.get(anyString())).thenReturn(null);

        service.recordFailure("Alice", "1.1.1.1");

        assertThat(localRateLimiter.count(service.key("alice", "1.1.1.1"), 600)).isEqualTo(1);
        assertThat(localRateLimiter.count(service.ipKey("1.1.1.1"), 600)).isEqualTo(1);
    }

    @Test
    void 登录成功清零用户与IP计数() {
        LoginThrottleService service = service(properties(true, 5, 10));
        when(ops.get(anyString())).thenReturn("3");

        service.clear("alice", "1.1.1.1");
        verify(redis).delete(service.key("alice", "1.1.1.1"));
        verify(redis).delete(service.ipKey("1.1.1.1"));
        assertThat(localRateLimiter.count(service.key("alice", "1.1.1.1"), 600)).isZero();
    }

    @Test
    void 节流key对用户名大小写与空白归一化() {
        LoginThrottleService service = service(properties(true, 5, 10));
        assertThat(service.key(" Alice ", "1.1.1.1"))
                .isEqualTo(service.key("alice", "1.1.1.1"));
        assertThat(service.key("alice", null)).isEqualTo(service.key("alice", "unknown"));
        assertThat(service.key("alice", "1.1.1.1")).startsWith("atry:login:attempt:");
        assertThat(service.key("alice", "1.1.1.1")).hasSize("atry:login:attempt:".length() + 64);
        assertThat(service.key("alice", "1.1.1.1"))
                .isNotEqualTo(service.key("alice", "2.2.2.2"));
    }

    @Test
    void 公开接口节流达到上限返回429且计数来自Redis() {
        PublicRequestThrottleService service = new PublicRequestThrottleService(redis, registry,
                localRateLimiter, new RedisFallbackMetrics(registry));
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(5L);

        service.check("invite-preview", "1.1.1.1", 5, 10); // 等于上限放行
        assertThatThrownBy(() -> service.check("invite-preview", "1.1.1.1", 4, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请求过于频繁");
    }

    @Test
    void 公开接口Redis故障时降级本地限流() {
        PublicRequestThrottleService service = new PublicRequestThrottleService(redis, registry,
                localRateLimiter, new RedisFallbackMetrics(registry));
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        service.check("invite-accept", "1.1.1.1", 1, 10);
        assertThatThrownBy(() -> service.check("invite-accept", "1.1.1.1", 1, 10))
                .isInstanceOf(BizException.class);
    }
}
