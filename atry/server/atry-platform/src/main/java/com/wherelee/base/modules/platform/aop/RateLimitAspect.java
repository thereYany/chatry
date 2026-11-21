package com.wherelee.base.modules.platform.aop;

import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.common.web.ClientIps;
import com.wherelee.base.modules.platform.config.SecurityProperties;
import com.wherelee.base.modules.platform.resilience.LocalRateLimiter;
import com.wherelee.base.modules.platform.resilience.RedisFallbackMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 限流切面：注解上的 limit/window 是默认值，可用配置覆盖（{@code app.rate-limit.<key>.limit}）。
 *
 * <p>key 支持 USER/IP/ANONYMOUS 维度；IP 维度只哈希落 Redis。命中时抛 429，
 * 同时打指标并发布 {@link RateLimitExceededEvent}（认证模块据此补审计）。</p>
 */
@Aspect
@Component
@Order(AspectOrders.RATE_LIMIT)
public class RateLimitAspect {

    private static final String LUA =
            "local current = redis.call('incr', KEYS[1]); " +
            "if current == 1 then redis.call('expire', KEYS[1], ARGV[1]); end; " +
            "return current;";

    private final StringRedisTemplate redis;
    private final LocalRateLimiter localRateLimiter;
    private final RedisFallbackMetrics fallbackMetrics;
    private final SecurityProperties securityProperties;
    private final Environment environment;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry registry;

    /** 兼容旧测试/手工构造：无配置覆盖、无指标、无事件。 */
    public RateLimitAspect(StringRedisTemplate redis,
                           LocalRateLimiter localRateLimiter,
                           RedisFallbackMetrics fallbackMetrics) {
        this(redis, localRateLimiter, fallbackMetrics, null, null, null, null);
    }

    /** 兼容带安全配置的旧构造。 */
    public RateLimitAspect(StringRedisTemplate redis,
                           LocalRateLimiter localRateLimiter,
                           RedisFallbackMetrics fallbackMetrics,
                           SecurityProperties securityProperties) {
        this(redis, localRateLimiter, fallbackMetrics, securityProperties, null, null, null);
    }

    @Autowired
    public RateLimitAspect(StringRedisTemplate redis,
                           LocalRateLimiter localRateLimiter,
                           RedisFallbackMetrics fallbackMetrics,
                           SecurityProperties securityProperties,
                           Environment environment,
                           ApplicationEventPublisher eventPublisher,
                           MeterRegistry registry) {
        this.redis = redis;
        this.localRateLimiter = localRateLimiter;
        this.fallbackMetrics = fallbackMetrics;
        this.securityProperties = securityProperties;
        this.environment = environment;
        this.eventPublisher = eventPublisher;
        this.registry = registry;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String annotationKey = rateLimit.key().isBlank()
                ? joinPoint.getSignature().toShortString()
                : rateLimit.key();
        int limit = limitOf(annotationKey, rateLimit.limit());
        int windowSeconds = windowOf(annotationKey, rateLimit.windowSeconds());
        String subject = subjectOf(rateLimit.dimension());
        String auditSubject = auditSubjectOf(rateLimit.dimension());
        String key = "atry:rate:" + annotationKey + ":" + subject;
        boolean localAllowed;
        try {
            localAllowed = localRateLimiter.allow(key, limit, windowSeconds);
        } catch (Exception e) {
            throw new BizException(HttpStatus.SERVICE_UNAVAILABLE, "请求保护暂时不可用，请稍后重试");
        }
        try {
            Long count = redis.execute(
                    new DefaultRedisScript<>(LUA, Long.class),
                    List.of(key),
                    String.valueOf(windowSeconds));
            if (count != null && count > limit) {
                reject(annotationKey, auditSubject);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            fallbackMetrics.record("rate_limit", "redis_error");
            if (!localAllowed) {
                reject(annotationKey, auditSubject);
            }
        }
        return joinPoint.proceed();
    }

    private void reject(String annotationKey, String subject) {
        if (registry != null) {
            Counter.builder("atry.rate_limit.blocked").tag("key", annotationKey).register(registry).increment();
        }
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new RateLimitExceededEvent(annotationKey, subject));
        }
        throw new BizException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
    }

    private int limitOf(String key, int fallback) {
        if (environment == null) {
            return fallback;
        }
        return environment.getProperty(propertyPrefix(key) + "limit", Integer.class, fallback);
    }

    private int windowOf(String key, int fallback) {
        if (environment == null) {
            return fallback;
        }
        return environment.getProperty(propertyPrefix(key) + "window-seconds", Integer.class, fallback);
    }

    private String propertyPrefix(String key) {
        return "app.rate-limit." + key.replace(':', '.') + ".";
    }

    private String subjectOf(RateLimit.Dimension dimension) {
        if (dimension == RateLimit.Dimension.ANONYMOUS) {
            return "anonymous";
        }
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return "anonymous";
            }
            if (dimension == RateLimit.Dimension.IP) {
                boolean trustForwarded = securityProperties != null
                        && securityProperties.trustForwardedHeaders();
                // Redis 键里不落明文 IP（与登录节流一致）；审计事件里带真实 IP
                return "ip:" + sha256(ClientIps.resolve(attributes.getRequest(), trustForwarded));
            }
            Object userId = attributes.getRequest().getAttribute("userId");
            return userId == null ? "anonymous" : String.valueOf(userId);
        } catch (Exception e) {
            return "anonymous";
        }
    }

    /** 供事件/审计使用的真实主体（IP 或 userId），不带哈希。 */
    private String auditSubjectOf(RateLimit.Dimension dimension) {
        if (dimension == RateLimit.Dimension.ANONYMOUS) {
            return "anonymous";
        }
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return "unknown";
            }
            if (dimension == RateLimit.Dimension.IP) {
                boolean trustForwarded = securityProperties != null
                        && securityProperties.trustForwardedHeaders();
                return ClientIps.resolve(attributes.getRequest(), trustForwarded);
            }
            Object userId = attributes.getRequest().getAttribute("userId");
            return userId == null ? "anonymous" : String.valueOf(userId);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
