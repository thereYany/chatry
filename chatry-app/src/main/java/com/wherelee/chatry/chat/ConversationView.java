package com.wherelee.chatry.chat;

import java.time.Instant;

/** 会话列表项。 */
public record ConversationView(long id, String title, String model, int messageCount,
                               Instant lastMessageAt, Instant createdAt, Instant updatedAt) {

    public static ConversationView from(ChatConversation conversation) {
        return new ConversationView(conversation.id(), conversation.title(), conversation.model(),
                conversation.messageCount(), conversation.lastMessageAt(),
                conversation.createdAt(), conversation.updatedAt());
    }
}
