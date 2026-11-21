package com.wherelee.base.modules.identity.rbac;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 权限缓存失效广播：本机改权限后通知其它实例清本地缓存。
 *
 * <p>Redis 不可用时只记日志——本机缓存已失效，其它实例最迟等 TTL（2 分钟）自然过期。</p>
 */
@Component
public class RbacCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(RbacCacheInvalidator.class);

    private final StringRedisTemplate redis;

    public RbacCacheInvalidator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void publishUser(long tenantId, long userId) {
        publish(RbacCacheChannels.userMessage(tenantId, userId));
    }

    public void publishAll() {
        publish(RbacCacheChannels.ALL);
    }

    private void publish(String payload) {
        try {
            redis.convertAndSend(RbacCacheChannels.CHANNEL, payload);
        } catch (RuntimeException e) {
            log.warn("RBAC 缓存失效广播失败 payload={} cause={}", payload, e.getClass().getSimpleName());
        }
    }
}
