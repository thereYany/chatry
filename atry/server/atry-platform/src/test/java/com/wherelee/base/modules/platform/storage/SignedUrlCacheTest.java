package com.wherelee.base.modules.platform.storage;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 签名 URL 缓存测试。
 *
 * <p>不变式：本地缓存命中不重复签名；Redis 多实例缓存命中同样不重复签名；
 * Redis 完全不可用时仍必须能生成 URL（可用性优先于缓存一致性）。</p>
 */
class SignedUrlCacheTest {

    private CosObjectStorage storage;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private SimpleMeterRegistry registry;
    private SignedUrlCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        storage = mock(CosObjectStorage.class);
        when(storage.presignedGetUrl(anyString(), any(Duration.class)))
                .thenAnswer(invocation -> "https://cos.example.com/" + invocation.getArgument(0));
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        registry = new SimpleMeterRegistry();
        StorageProperties properties = new StorageProperties(
                true, "bucket", "region", 240, 3000, 15000, 2, 30);
        cache = new SignedUrlCache(storage, properties, redis, new ObjectMapper(),
                registry, CircuitBreakerRegistry.ofDefaults(),
                new com.wherelee.base.modules.platform.resilience.RedisFallbackMetrics(registry));
    }

    @Test
    void 首次未命中生成签名并缓存_第二次本地命中() {
        when(ops.get(anyString())).thenReturn(null);

        SignedUrlCache.CachedUrl first = cache.get("a/b.png");
        assertThat(first.url()).isEqualTo("https://cos.example.com/a/b.png");
        assertThat(first.cacheHit()).isFalse();
        assertThat(first.expiresAt()).isAfter(Instant.now());

        SignedUrlCache.CachedUrl second = cache.get("a/b.png");
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.url()).isEqualTo(first.url());

        // 本地命中后不得再次签名
        verify(storage).presignedGetUrl(eq("a/b.png"), any(Duration.class));
    }

    @Test
    void Redis缓存命中时直接复用且不回源签名() {
        long future = Instant.now().plusSeconds(3600).getEpochSecond();
        when(ops.get(anyString())).thenReturn(
                "{\"url\":\"https://cached.example.com/a/b.png\",\"expiresAt\":" + future + "}");

        SignedUrlCache.CachedUrl result = cache.get("a/b.png");
        assertThat(result.url()).isEqualTo("https://cached.example.com/a/b.png");
        assertThat(result.cacheHit()).isTrue();
        verify(storage, never()).presignedGetUrl(anyString(), any(Duration.class));
    }

    @Test
    void Redis过期缓存不会被复用() {
        when(ops.get(anyString())).thenReturn(
                "{\"url\":\"https://stale.example.com/a.png\",\"expiresAt\":"
                        + Instant.now().minusSeconds(10).getEpochSecond() + "}");

        SignedUrlCache.CachedUrl result = cache.get("a.png");
        // 缓存已过期：必须回源重新签名，不能返回即将失效的 URL
        assertThat(result.url()).isEqualTo("https://cos.example.com/a.png");
        assertThat(result.cacheHit()).isFalse();
        verify(storage).presignedGetUrl(eq("a.png"), any(Duration.class));
    }

    @Test
    void Redis完全不可用时降级为本地签名保证可用() {
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        SignedUrlCache.CachedUrl result = cache.get("degraded.png");
        assertThat(result.url()).isEqualTo("https://cos.example.com/degraded.png");
        assertThat(result.cacheHit()).isFalse();
        // 降级必须被度量（infra.redis.fallback）
        assertThat(registry.get("infra.redis.fallback")
                .tag("component", "storage_url_cache").counters())
                .isNotEmpty();
    }
}
