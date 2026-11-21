package com.wherelee.base.modules.contracts.llm;

import java.time.Instant;

/** 一次逻辑调用成功完成事件（供 metering 记账/观测消费；不含内容）。 */
public record LlmCallCompleted(
        String requestId,
        long tenantId,
        long appId,
        long userId,
        String provider,
        String modelServed,
        LlmUsage usage,
        long latencyMs,
        Instant occurredAt
) {
}
