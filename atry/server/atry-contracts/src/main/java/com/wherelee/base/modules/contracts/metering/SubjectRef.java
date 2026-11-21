package com.wherelee.base.modules.contracts.metering;

/** 计量主体：租户 → 应用 → 用户（只存 ID）。 */
public record SubjectRef(long tenantId, long appId, long userId) {
}
