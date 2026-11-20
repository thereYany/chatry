package com.wherelee.chatry.chat;

import java.time.Instant;

/** 消息视图：给前端回放历史用，不暴露内部请求号。 */
public record ChatMessageView(long id, String role, String content, int inputTokens, int outputTokens,
                          long costMicros, String status, Instant createdAt) {

    public static ChatMessageView from(ChatMessageEntity message) {
        return new ChatMessageView(message.id(), message.role(), message.content(),
                message.inputTokens(), message.outputTokens(), message.costMicros(),
                message.status(), message.createdAt());
    }
}
