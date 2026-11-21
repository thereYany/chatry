package com.wherelee.base.modules.platform.aop;

import com.wherelee.base.modules.platform.audit.AdminAuditRepository;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.resilience.LocalRateLimiter;
import com.wherelee.base.modules.platform.resilience.RedisFallbackMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台切面测试：限流、审计、度量。
 *
 * <p>不变式：限流超限抛 429 且不执行业务；Redis 故障时降级本地限流；
 * 管理操作审计只写操作人存在且能定位目标；未知异常不得影响业务主流程。</p>
 */
class PlatformAspectsTest {

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------- @RateLimit ----------

    @Test
    void 限流Redis计数超限时拒绝() {
        RateLimitParent parent = new RateLimitParent();
        parent.redisResult = 3L;
        parent.redisAvailable = true;
        assertThatThrownBy(() -> parent.proxy.limited("a"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请求过于频繁");
        assertThat(parent.executions.get()).isZero();
    }

    @Test
    void 限流未超限时放行() {
        RateLimitParent parent = new RateLimitParent();
        parent.redisResult = 1L;
        parent.redisAvailable = true;
        assertThat(parent.proxy.limited("a")).isEqualTo("ok");
        assertThat(parent.executions.get()).isEqualTo(1);
    }

    @Test
    void Redis故障时降级本地限流_本地拒绝则返回429() {
        RateLimitParent parent = new RateLimitParent();
        parent.redisAvailable = false;
        // limit=1：本地窗口内第一次放行、第二次拒绝
        assertThat(parent.proxy.limited("a")).isEqualTo("ok");
        assertThatThrownBy(() -> parent.proxy.limited("a")).isInstanceOf(BizException.class);
    }

    @Test
    void 限流key包含登录用户ID() {
        RateLimitParent parent = new RateLimitParent();
        parent.redisResult = 1L;
        parent.redisAvailable = true;
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x");
        request.setAttribute("userId", 42L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        parent.proxy.limited("a");
        // 限流 key 必须包含登录用户维度，不能只按接口维度限流（否则一个用户打满影响所有人）
        assertThat(parent.redis.lastKeys).containsExactly("atry:rate:unit:rate:42");
    }

    // ---------- @AdminAudit ----------

    @Test
    void 成功操作写审计且目标ID取自结果或参数() {
        AdminAuditRepository repository = mock(AdminAuditRepository.class);
        AuditParent parent = new AuditParent(repository);
        RequestContextHolder.setRequestAttributes(userRequest(7L));

        parent.proxy.changeStatus(99L, "DISABLED");
        verify(repository).insert(eq(7L), eq("USER_STATUS_CHANGE"), eq("USER"), eq(99L),
                eq(true), anyLong(), isNull(), isNull());

        parent.proxy.createWithResult();
        verify(repository).insert(eq(7L), eq("USER_CREATE"), eq("USER"), eq(123L),
                eq(true), anyLong(), isNull(), isNull());

        parent.proxy.createAuditedResult();
        verify(repository).insert(eq(7L), eq("INVITE_CREATE"), eq("INVITATION"), eq(456L),
                eq(true), anyLong(), isNull(), isNull());
    }

    @Test
    void 失败操作写审计且记录错误消息() {
        AdminAuditRepository repository = mock(AdminAuditRepository.class);
        AuditParent parent = new AuditParent(repository);
        RequestContextHolder.setRequestAttributes(userRequest(7L));

        assertThatThrownBy(() -> parent.proxy.fail(9L)).isInstanceOf(IllegalStateException.class);
        verify(repository).insert(eq(7L), eq("USER_STATUS_CHANGE"), eq("USER"), eq(9L),
                eq(false), anyLong(), contains("模拟失败"), isNull());
    }

    @Test
    void 无登录操作人时不写审计() {
        AdminAuditRepository repository = mock(AdminAuditRepository.class);
        AuditParent parent = new AuditParent(repository);
        RequestContextHolder.resetRequestAttributes();

        parent.proxy.changeStatus(99L, "DISABLED");
        verify(repository, never()).insert(any(), anyString(), anyString(), any(), anyBoolean(),
                anyLong(), any(), any());
    }

    @Test
    void 审计写入失败不影响业务返回() {
        AdminAuditRepository repository = mock(AdminAuditRepository.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(repository).insert(any(), anyString(), anyString(), any(), anyBoolean(),
                        anyLong(), any(), any());
        AuditParent parent = new AuditParent(repository);
        RequestContextHolder.setRequestAttributes(userRequest(7L));

        assertThat(parent.proxy.changeStatus(99L, "DISABLED")).isEqualTo("done");
    }

    // ---------- @Measured ----------

    @Test
    void 度量切面记录耗时且异常也记录() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeasuredAspect aspect = new MeasuredAspect(registry);
        AspectJProxyFactory factory = new AspectJProxyFactory(new MeasuredTarget());
        factory.addAspect(aspect);
        MeasuredTarget proxy = factory.getProxy();

        proxy.success();
        assertThatThrownBy(proxy::failure).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("atry.method").tag("method", "unit.success").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("atry.method").tag("method", "unit.failure").timer().count())
                .isEqualTo(1);
    }

    private static ServletRequestAttributes userRequest(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin");
        request.setAttribute("userId", userId);
        return new ServletRequestAttributes(request);
    }

    /** 可脚本化 Redis 的 @RateLimit 目标。 */
    static class RateLimitParent {
        final AtomicInteger executions = new AtomicInteger();
        final StubRedis redis = new StubRedis();
        final RateLimitedTarget proxy;

        RateLimitParent() {
            RateLimitAspect aspect = new RateLimitAspect(redis,
                    new LocalRateLimiter(),
                    new RedisFallbackMetrics(new SimpleMeterRegistry()));
            AspectJProxyFactory factory = new AspectJProxyFactory(new RateLimitedTarget(executions));
            factory.addAspect(aspect);
            proxy = factory.getProxy();
        }

        Long redisResult;
        boolean redisAvailable = true;

        {
            redis.resultProvider = () -> redisResult;
            redis.failProvider = () -> !redisAvailable;
        }
    }

    public static class RateLimitedTarget {
        private final AtomicInteger executions;

        public RateLimitedTarget(AtomicInteger executions) {
            this.executions = executions;
        }

        @RateLimit(key = "unit:rate", limit = 1, windowSeconds = 60)
        public String limited(String arg) {
            executions.incrementAndGet();
            return "ok";
        }
    }

    /** 绕过连接工厂的 Redis 桩：只实现切面用到的 execute。 */
    static class StubRedis extends StringRedisTemplate {
        java.util.function.Supplier<Long> resultProvider = () -> 1L;
        java.util.function.BooleanSupplier failProvider = () -> false;
        List<String> lastKeys = List.of();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            lastKeys = List.copyOf(keys);
            if (failProvider.getAsBoolean()) {
                throw new RedisConnectionFailureException("mock redis down");
            }
            return (T) resultProvider.get();
        }
    }

    static class AuditParent {
        final AuditTarget proxy;

        AuditParent(AdminAuditRepository repository) {
            AspectJProxyFactory factory = new AspectJProxyFactory(new AuditTarget());
            factory.addAspect(new AdminAuditAspect(repository));
            proxy = factory.getProxy();
        }
    }

    public static class AuditTarget {
        @AdminAudit(action = "USER_STATUS_CHANGE", targetType = "USER", targetIdArg = 0)
        public String changeStatus(Long id, String status) {
            return "done";
        }

        @AdminAudit(action = "USER_CREATE", targetType = "USER", targetIdFromResult = true)
        public Long createWithResult() {
            return 123L;
        }

        @AdminAudit(action = "INVITE_CREATE", targetType = "INVITATION", targetIdFromResult = true)
        public AuditedResult createAuditedResult() {
            return () -> 456L;
        }

        @AdminAudit(action = "USER_STATUS_CHANGE", targetType = "USER", targetIdArg = 0)
        public void fail(Long id) {
            throw new IllegalStateException("模拟失败");
        }
    }

    public static class MeasuredTarget {
        @Measured("unit.success")
        public void success() {
        }

        @Measured("unit.failure")
        public void failure() {
            throw new IllegalStateException("boom");
        }
    }
}
