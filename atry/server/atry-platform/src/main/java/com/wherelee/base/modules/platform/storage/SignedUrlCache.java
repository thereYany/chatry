package com.wherelee.base.modules.platform.storage;

import tools.jackson.databind.ObjectMapper;
import com.wherelee.base.modules.platform.aop.Measured;
import com.wherelee.base.modules.platform.resilience.RedisFallbackMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.enabled", havingValue = "true")
public class SignedUrlCache {

    private static final String KEY_PREFIX = "atry:storage:url:";
    private static final String LOCK_PREFIX = "atry:storage:lock:";
    private static final String RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final CosObjectStorage objectStorage;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration urlTtl;
    private final Duration freshFor;
    private final ConcurrentHashMap<String, Entry> localCache = new ConcurrentHashMap<>();

    private final Counter localHit;
    private final Counter redisHit;
    private final Counter miss;
    private final Counter redisError;
    private final Counter lockWait;
    private final Counter lockTimeout;
    private final Timer presignTimer;
    private final CircuitBreaker redisCircuitBreaker;
    private final RedisFallbackMetrics fallbackMetrics;

    public SignedUrlCache(CosObjectStorage objectStorage,
                            StorageProperties properties,
                            StringRedisTemplate redis,
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry,
                            CircuitBreakerRegistry circuitBreakerRegistry,
                            RedisFallbackMetrics fallbackMetrics) {
        this.objectStorage = objectStorage;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.urlTtl = Duration.ofMinutes(properties.urlTtlMinutes());
        Duration configuredMargin = Duration.ofMinutes(Math.max(0, properties.urlCacheMarginMinutes()));
        Duration minimumMargin = urlTtl.dividedBy(10);
        Duration effectiveMargin = configuredMargin.compareTo(minimumMargin) > 0
                ? configuredMargin
                : minimumMargin;
        this.freshFor = urlTtl.minus(effectiveMargin).isNegative()
                ? Duration.ZERO
                : urlTtl.minus(effectiveMargin);

        this.localHit = counter(meterRegistry, "local_hit");
        this.redisHit = counter(meterRegistry, "redis_hit");
        this.miss = counter(meterRegistry, "miss");
        this.redisError = counter(meterRegistry, "redis_error");
        this.lockWait = counter(meterRegistry, "lock_wait");
        this.lockTimeout = counter(meterRegistry, "lock_timeout");
        this.presignTimer = Timer.builder("storage.url.presign").register(meterRegistry);
        this.redisCircuitBreaker = circuitBreakerRegistry.circuitBreaker("redis");
        this.fallbackMetrics = fallbackMetrics;
        Gauge.builder("storage.url.cache.local.size", localCache, Map::size).register(meterRegistry);
    }

    @Measured("storage.urlCache.get")
    public CachedUrl get(String objectKey) {
        Instant now = Instant.now();
        Entry local = localCache.get(objectKey);
        if (local != null && local.freshUntil().isAfter(now)) {
            localHit.increment();
            return new CachedUrl(local.url(), local.expiresAt(), true);
        }

        Entry redisEntry = readRedis(objectKey, now);
        if (redisEntry != null) {
            localCache.put(objectKey, redisEntry);
            redisHit.increment();
            return new CachedUrl(redisEntry.url(), redisEntry.expiresAt(), true);
        }

        miss.increment();
        String lockKey = LOCK_PREFIX + objectKey;
        String token = UUID.randomUUID().toString();
        boolean locked = false;
        try {
            locked = Boolean.TRUE.equals(redisCircuitBreaker.executeSupplier(
                    () -> redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofSeconds(5))));
        } catch (Exception e) {
            redisError.increment();
            fallbackMetrics.record("storage_url_cache", "lock");
            log.warn("Redis 单飞锁不可用，降级为本地生成 key={} cause={}", objectKey, e.toString());
        }

        if (locked) {
            try {
                Entry generated = generate(objectKey);
                localCache.put(objectKey, generated);
                writeRedis(objectKey, generated);
                return new CachedUrl(generated.url(), generated.expiresAt(), false);
            } finally {
                releaseLock(lockKey, token);
            }
        }

        if (!locked && redisAvailableHint()) {
            lockWait.increment();
            for (int i = 0; i < 30; i++) {
                sleepQuietly(50);
                Entry waited = readRedis(objectKey, Instant.now());
                if (waited != null) {
                    localCache.put(objectKey, waited);
                    redisHit.increment();
                    return new CachedUrl(waited.url(), waited.expiresAt(), true);
                }
            }
            lockTimeout.increment();
        }

        Entry generated = generate(objectKey);
        localCache.put(objectKey, generated);
        return new CachedUrl(generated.url(), generated.expiresAt(), false);
    }

    private Entry readRedis(String objectKey, Instant now) {
        try {
            String json = redisCircuitBreaker.executeSupplier(
                    () -> redis.opsForValue().get(KEY_PREFIX + objectKey));
            if (json == null) {
                return null;
            }
            RedisEntry value = objectMapper.readValue(json, RedisEntry.class);
            Instant expiresAt = Instant.ofEpochSecond(value.expiresAt());
            if (!expiresAt.isAfter(now.plusSeconds(60))) {
                return null;
            }
            Instant freshUntil = now.plus(freshFor);
            Instant safeUntil = expiresAt.minus(urlTtl.minus(freshFor));
            if (safeUntil.isBefore(freshUntil)) {
                freshUntil = safeUntil;
            }
            if (!freshUntil.isAfter(now)) {
                return null;
            }
            return new Entry(value.url(), expiresAt, freshUntil);
        } catch (Exception e) {
            redisError.increment();
            fallbackMetrics.record("storage_url_cache", "read");
            log.warn("读取 Redis 签名 URL 失败，降级为本地生成 key={} cause={}", objectKey, e.toString());
            return null;
        }
    }

    private void writeRedis(String objectKey, Entry entry) {
        try {
            long base = Math.max(1, freshFor.getSeconds());
            long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, Math.min(30, base / 10)) + 1);
            long ttlSeconds = Math.max(1, base - jitter);
            String json = objectMapper.writeValueAsString(
                    new RedisEntry(entry.url(), entry.expiresAt().getEpochSecond()));
            redisCircuitBreaker.executeRunnable(
                    () -> redis.opsForValue().set(KEY_PREFIX + objectKey, json, Duration.ofSeconds(ttlSeconds)));
        } catch (Exception e) {
            redisError.increment();
            fallbackMetrics.record("storage_url_cache", "write");
            log.warn("写入 Redis 签名 URL 失败，仅保留本地缓存 key={} cause={}", objectKey, e.toString());
        }
    }

    private Entry generate(String objectKey) {
        Instant generatedAt = Instant.now();
        String url = presignTimer.record(() -> objectStorage.presignedGetUrl(objectKey, urlTtl));
        return new Entry(url, generatedAt.plus(urlTtl), generatedAt.plus(freshFor));
    }

    private void releaseLock(String lockKey, String token) {
        try {
            redisCircuitBreaker.executeRunnable(
                    () -> redis.execute(new DefaultRedisScript<>(RELEASE_LUA, Long.class), List.of(lockKey), token));
        } catch (Exception e) {
            redisError.increment();
            fallbackMetrics.record("storage_url_cache", "lock_release");
            log.warn("释放 Redis 单飞锁失败 lockKey={} cause={}", lockKey, e.toString());
        }
    }

    private boolean redisAvailableHint() {
        try {
            redisCircuitBreaker.executeSupplier(() -> redis.hasKey(KEY_PREFIX + "probe"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder("storage.url.cache")
                .tag("result", result)
                .register(registry);
    }

    private static void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record CachedUrl(String url, Instant expiresAt, boolean cacheHit) {
    }

    private record Entry(String url, Instant expiresAt, Instant freshUntil) {
    }

    public record RedisEntry(String url, long expiresAt) {
    }
}
