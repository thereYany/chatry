package com.wherelee.base.modules.contracts.llm;

import java.time.Instant;
import java.util.List;

/** 统一 LLM 请求（业务只依赖此契约，不依赖具体 provider）。 */
public record LlmRequest(
        String requestId,
        long tenantId,
        long appId,
        long userId,
        String model,
        List<ChatMessage> messages,
        Integer maxTokens,
        boolean stream,
        Instant deadlineAt,
        String budgetRef
) {
}
