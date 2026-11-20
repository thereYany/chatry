package com.wherelee.chatry.chat;

import java.time.Instant;

/**
 * 消息（对话历史的持久化视图）。
 *
 * <p>与内核契约 {@code com.wherelee.base.modules.contracts.llm.ChatMessage} 区分：
 * 这是业务表的一行，多了用量、成本、状态与请求号，便于对账与前端回放。</p>
 */
public record ChatMessageEntity(
        long id,
        long tenantId,
        long conversationId,
        long userId,
        String role,
        String content,
        int inputTokens,
        int outputTokens,
        int cachedReadTokens,
        long costMicros,
        String llmRequestId,
        String provider,
        String modelServed,
        String finishReason,
        String status,
        Instant createdAt
) {
}
