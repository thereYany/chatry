package com.wherelee.base.modules.platform.audit;

import java.time.LocalDateTime;

/** 管理操作审计检索视图。 */
public record AdminAuditView(
        Long id,
        Long operatorId,
        String action,
        String targetType,
        Long targetId,
        boolean success,
        long durationMs,
        String errorMessage,
        String traceId,
        LocalDateTime createdAt
) {
}
