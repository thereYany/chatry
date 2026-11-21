package com.wherelee.chatry.chat;

import java.time.Instant;

/** 会话（一个用户的一串对话）。 */
public record ChatConversation(
        long id,
        long tenantId,
        long userId,
        String title,
        String model,
        String status,
        int messageCount,
        Instant lastMessageAt,
        Instant createdAt,
        Instant updatedAt
) {
}
