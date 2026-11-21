package com.wherelee.base.modules.identity.auth;

import java.time.LocalDateTime;

/** 认证事件检索视图（管理端）。 */
public record AuthEventView(
        Long id,
        Long userId,
        String username,
        String eventType,
        boolean success,
        String ip,
        String userAgent,
        String detail,
        LocalDateTime createdAt
) {
}
