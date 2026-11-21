package com.wherelee.base.modules.identity.session;

import java.time.Instant;

/** 会话视图：不暴露 token，只暴露定位信息与当前会话标记。 */
public record SessionView(String sessionId, String ip, String userAgent,
                          Instant createdAt, Instant expiresAt, boolean current) {
}
