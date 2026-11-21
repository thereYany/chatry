package com.wherelee.base.modules.llm.resilience;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.observability.LlmMetrics;
import com.wherelee.base.modules.platform.resilience.RedisFallbackMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 多维限流：租户 RPM + provider RPM + 每 provider 在途并发（舱壁）。
 *
 * <p>多实例一致性：租户/provider 的令牌桶放在 Redis（Lua 原子扣减 + Redis TIME 取时，
 * 避免实例时钟漂移），多个应用实例共享同一份额度；Redis 不可用时自动降级为
 * 每实例本地令牌桶（按实例满额，宁可放宽也不 fail-closed），并记
 * {@code infra.redis.fallback{component=llm_rate_limit}}。</p>
 *
 * <p>舱壁（在途并发）本身就是「每实例」语义，不需要共享。</p>
 */
@Component
public class LlmRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LlmRateLimiter.class);

    /** 令牌桶容量 = max(1, rpm/30)（约 2 秒突发），与本地实现保持一致。 */
    private static final String REDIS_KEY_PREFIX = "atry:llm:rate:";

    /**
     * KEYS[1]=桶 key；ARGV[1]=rpm；ARGV[2]=capacity。
     * 返回 1 允许、0 拒绝；TIME 用 Redis 服务端时间，保证多实例使用同一时钟。
     */
    private static final String TOKEN_BUCKET_LUA = """
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local rpm = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil then
                tokens = capacity
                ts = now
            end
            local delta = math.max(0, now - ts)
            tokens = math.min(capacity, tokens + delta * rpm / 60000.0)
            local allowed = 0
            if tokens >= 1.0 then
                tokens = tokens - 1.0
                allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 60000.0 / rpm) + 1000)
            return allowed
            """;

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT =
            new DefaultRedisScript<>(TOKEN_BUCKET_LUA, Long.class);

    private final int tenantRpm;
    private final int providerRpm;
    private final int maxInFlight;
    private final StringRedisTemplate redis;
    private final RedisFallbackMetrics fallbackMetrics;
    private final LlmMetrics metrics;
    private final ConcurrentHashMap<String, TokenBucket> localBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> inFlight = new ConcurrentHashMap<>();

    /**
     * @param redis          Redis 模板；{@code null} 或 backend=local 时只用本地令牌桶（单测/单机模式）
     * @param fallbackMetrics Redis 降级指标；可为 null（单测）
     * @param metrics        限流拒绝指标；可为 null（单测）
     */
    @Autowired
    public LlmRateLimiter(LlmProperties properties, StringRedisTemplate redis,
                          RedisFallbackMetrics fallbackMetrics, LlmMetrics metrics) {
        LlmProperties.RateLimit config = properties.rateLimit();
        this.tenantRpm = Math.max(1, config.tenantRpm());
        this.providerRpm = Math.max(1, config.providerRpm());
        this.maxInFlight = Math.max(1, config.maxInFlight());
        String backend = config.backend() == null ? "auto" : config.backend().trim().toLowerCase();
        this.redis = redis != null && !"local".equals(backend) ? redis : null;
        this.fallbackMetrics = fallbackMetrics;
        this.metrics = metrics;
        if (this.redis == null && !"local".equals(backend)) {
            log.info("llm.rate_limit.backend=local（未注入 Redis，单机本地令牌桶）");
        }
    }

    /** 单机/单测便捷构造：只用本地令牌桶。 */
    public LlmRateLimiter(LlmProperties properties) {
        this(properties, null, null, null);
    }

    /** 申请许可；失败抛出 RATE_LIMITED / UPSTREAM_OVERLOADED。返回的 providerId 用于 release。 */
    public String acquire(String providerId, long tenantId) {
        Semaphore semaphore = inFlight.computeIfAbsent(providerId, ignored -> new Semaphore(maxInFlight));
        if (!semaphore.tryAcquire()) {
            throw new LlmException(new LlmError(LlmErrorCategory.UPSTREAM_OVERLOADED, "BULKHEAD_FULL",
                    false, false, "provider 在途并发已满：" + providerId));
        }
        try {
            if (!tryAcquire("tenant:" + tenantId, tenantRpm)) {
                if (metrics != null) {
                    metrics.rejected("tenant");
                }
                throw new LlmException(new LlmError(LlmErrorCategory.RATE_LIMITED, "TENANT_RATE_LIMITED",
                        true, false, "租户请求速率超限"));
            }
            if (!tryAcquire("provider:" + providerId, providerRpm)) {
                if (metrics != null) {
                    metrics.rejected("provider");
                }
                throw new LlmException(new LlmError(LlmErrorCategory.RATE_LIMITED, "PROVIDER_RATE_LIMITED",
                        true, false, "provider 请求速率超限"));
            }
            return providerId;
        } catch (RuntimeException e) {
            semaphore.release();
            throw e;
        }
    }

    public void release(String providerId) {
        Semaphore semaphore = inFlight.get(providerId);
        if (semaphore != null) {
            semaphore.release();
        }
    }

    /** 先走 Redis 共享桶；Redis 异常时降级本地桶并记指标。 */
    private boolean tryAcquire(String key, int rpm) {
        if (redis != null) {
            try {
                Long allowed = redis.execute(TOKEN_BUCKET_SCRIPT,
                        List.of(REDIS_KEY_PREFIX + key),
                        String.valueOf(rpm), String.valueOf(Math.max(1, rpm / 30.0)));
                if (allowed != null) {
                    return allowed == 1L;
                }
            } catch (RuntimeException e) {
                if (fallbackMetrics != null) {
                    fallbackMetrics.record("llm_rate_limit", "redis_error");
                }
                log.warn("llm.rate_limit.redis_fallback key={} error={}", key, e.toString());
            }
        }
        return localBuckets.computeIfAbsent(key, ignored -> new TokenBucket(rpm)).tryAcquire();
    }

    /** 令牌桶：容量 = max(1, rpm/30)（约 2 秒突发），按纳秒补充（本地降级用）。 */
    static final class TokenBucket {
        private final double capacity;
        private final double tokensPerNano;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int rpm) {
            this.capacity = Math.max(1, rpm / 30.0);
            this.tokens = capacity;
            this.tokensPerNano = rpm / 60.0 / 1_000_000_000.0;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastRefillNanos) * tokensPerNano);
            lastRefillNanos = now;
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }
    }
}
