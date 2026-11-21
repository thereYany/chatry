package com.wherelee.base.modules.platform.aop;

import com.wherelee.base.modules.platform.common.BizException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 分布式锁切面：Redis SET NX PX 加锁，Lua 比对 token 解锁，支持短暂等待。
 *
 * <p>Redis 故障时 fail-closed（503）：支付/下单这类入口宁可不执行，也不能无锁并发写。</p>
 */
@Aspect
@Component
@Order(AspectOrders.DISTRIBUTED_LOCK)
public class DistributedLockAspect {

    private static final String UNLOCK_LUA = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>(UNLOCK_LUA, Long.class);
    private static final long POLL_INTERVAL_MS = 50L;

    private final StringRedisTemplate redis;
    private final MeterRegistry registry;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    public DistributedLockAspect(StringRedisTemplate redis, MeterRegistry registry) {
        this.redis = redis;
        this.registry = registry;
    }

    @Around("@annotation(lock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock lock) throws Throwable {
        String key = "atry:lock:" + resolveKey(joinPoint, lock);
        String token = UUID.randomUUID().toString();
        String metricKey = lock.key().isBlank() ? joinPoint.getSignature().toShortString() : lock.key();
        if (!acquire(key, token, lock, metricKey)) {
            Counter.builder("atry.lock.failed").tag("key", metricKey).register(registry).increment();
            throw new BizException(HttpStatus.CONFLICT, lock.failMessage());
        }
        Counter.builder("atry.lock.acquired").tag("key", metricKey).register(registry).increment();
        try {
            return joinPoint.proceed();
        } finally {
            release(key, token);
        }
    }

    private boolean acquire(String key, String token, DistributedLock lock, String metricKey) {
        long deadline = System.currentTimeMillis() + Math.max(0, lock.waitMs());
        long leaseMs = Math.max(1_000, lock.leaseMs());
        while (true) {
            try {
                Boolean acquired = redis.opsForValue()
                        .setIfAbsent(key, token, Duration.ofMillis(leaseMs));
                if (Boolean.TRUE.equals(acquired)) {
                    return true;
                }
            } catch (RuntimeException e) {
                throw new BizException(HttpStatus.SERVICE_UNAVAILABLE, "分布式锁服务不可用，请稍后重试");
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(Math.min(POLL_INTERVAL_MS, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void release(String key, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(key), token);
        } catch (RuntimeException ignored) {
            // 释放失败交给 lease 过期；这里不掩盖业务异常
        }
    }

    private String resolveKey(ProceedingJoinPoint joinPoint, DistributedLock lock) {
        String staticPart = lock.key().isBlank()
                ? joinPoint.getSignature().toShortString()
                : lock.key();
        String expressionValue = "";
        if (!lock.keyExpression().isBlank()) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                    joinPoint.getTarget(), signature.getMethod(), joinPoint.getArgs(), parameterNames);
            Expression expression = parser.parseExpression(lock.keyExpression());
            Object value = expression.getValue(context);
            expressionValue = value == null ? "null" : String.valueOf(value);
        }
        String fingerprint = expressionValue.isBlank()
                ? sha256(Arrays.deepToString(joinPoint.getArgs()))
                : expressionValue;
        return staticPart + ":" + fingerprint;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
