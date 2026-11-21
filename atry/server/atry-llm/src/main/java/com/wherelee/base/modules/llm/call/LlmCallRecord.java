package com.wherelee.base.modules.llm.call;

/** t_llm_call_record 的写入模型（只含脱敏元数据，不含内容）。 */
public record LlmCallRecord(
        String requestId,
        long tenantId,
        long appId,
        long userId,
        String operation,
        String provider,
        String deployment,
        String modelRequested,
        String modelServed,
        boolean stream,
        int attemptCount,
        String status,
        String errorCategory,
        String providerCode,
        Boolean retryable,
        Boolean billable,
        long inputTokens,
        long outputTokens,
        long cachedReadTokens,
        long cachedWriteTokens,
        long reasoningTokens,
        String usageSource,
        Long latencyMs,
        Long ttftMs
) {
}
