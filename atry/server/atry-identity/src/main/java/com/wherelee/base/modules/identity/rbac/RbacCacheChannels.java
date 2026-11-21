package com.wherelee.base.modules.identity.rbac;

/** RBAC 缓存失效广播的频道与消息格式（多实例共享）。 */
public final class RbacCacheChannels {

    public static final String CHANNEL = "atry:rbac:invalidate";
    public static final String ALL = "ALL";

    private RbacCacheChannels() {
    }

    public static String userMessage(long tenantId, long userId) {
        return tenantId + ":" + userId;
    }
}
