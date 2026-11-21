package com.wherelee.base.modules.identity.rbac;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 接收其它实例的缓存失效消息，只清本地缓存，不再广播（避免回声）。
 */
@Component
public class RbacCacheInvalidationListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RbacCacheInvalidationListener.class);

    private final PermissionService permissionService;

    public RbacCacheInvalidationListener(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8).trim();
        if (payload.isEmpty()) {
            return;
        }
        if (RbacCacheChannels.ALL.equals(payload)) {
            permissionService.invalidateAllLocal();
            log.info("rbac.cache.invalidated scope=all");
            return;
        }
        String[] parts = payload.split(":", 2);
        if (parts.length != 2) {
            log.warn("忽略无法解析的 RBAC 失效消息 payload={}", payload);
            return;
        }
        try {
            permissionService.invalidateLocal(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            log.info("rbac.cache.invalidated tenantId={} userId={}", parts[0], parts[1]);
        } catch (NumberFormatException e) {
            log.warn("忽略无法解析的 RBAC 失效消息 payload={}", payload);
        }
    }
}
