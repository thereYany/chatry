package com.wherelee.base.modules.platform.aop;

import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.config.SecurityProperties;
import com.wherelee.base.modules.platform.resilience.LocalRateLimiter;
import com.wherelee.base.modules.platform.resilience.RedisFallbackMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitAspectTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final LocalRateLimiter localRateLimiter = mock(LocalRateLimiter.class);
    private final RedisFallbackMetrics fallbackMetrics = mock(RedisFallbackMetrics.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void configOverridesAnnotationLimitAndPublishesEventOnBlock() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.rate-limit.auth.register.limit", "1")
                .withProperty("app.rate-limit.auth.register.window-seconds", "60");
        RateLimitAspect aspect = new RateLimitAspect(redis, localRateLimiter, fallbackMetrics,
                new SecurityProperties(List.of(), false, false, 8, 40, 10),
                environment, publisher, registry);
        when(localRateLimiter.allow(anyString(), anyInt(), anyInt())).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(2L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest("POST", "/api/auth/register")));

        Target proxy = proxy(aspect);
        assertThatThrownBy(proxy::register)
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.status().value()).isEqualTo(429));

        verify(publisher).publishEvent(new RateLimitExceededEvent("auth:register", "127.0.0.1"));
        assertThat(registry.get("atry.rate_limit.blocked").tag("key", "auth:register")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void passesThroughWhenUnderLimit() throws Throwable {
        RateLimitAspect aspect = new RateLimitAspect(redis, localRateLimiter, fallbackMetrics);
        when(localRateLimiter.allow(anyString(), anyInt(), anyInt())).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        Target proxy = proxy(aspect);
        assertThat(proxy.register()).isEqualTo("ok");
    }

    private Target proxy(RateLimitAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Target());
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    static class Target {

        @RateLimit(key = "auth:register", limit = 10, windowSeconds = 3600,
                dimension = RateLimit.Dimension.IP)
        public String register() {
            return "ok";
        }
    }
}
