package com.wherelee.chatry.chat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatContextBuilderTest {

    private final ChatProperties properties = new ChatProperties(
            "mock-small", 256, 3, 100, 30, 120, 60_000L, 20);
    private final ChatContextBuilder builder = new ChatContextBuilder(properties);

    @Test
    void keepsNewestMessagesAndRespectsMessageLimit() {
        List<ChatMessageEntity> history = List.of(
                message(1, ChatMessageRepository.ROLE_USER, "first"),
                message(2, ChatMessageRepository.ROLE_ASSISTANT, "first-answer"),
                message(3, ChatMessageRepository.ROLE_USER, "second"),
                message(4, ChatMessageRepository.ROLE_ASSISTANT, "second-answer"),
                message(5, ChatMessageRepository.ROLE_USER, "third"));

        List<com.wherelee.base.modules.contracts.llm.ChatMessage> context = builder.build(history);

        assertThat(context).hasSize(3);
        assertThat(context.get(0).role()).isEqualTo("user");
        assertThat(context.get(0).content()).isEqualTo("second");
        assertThat(context).extracting(com.wherelee.base.modules.contracts.llm.ChatMessage::content)
                .containsExactly("second", "second-answer", "third");
    }

    @Test
    void alwaysKeepsTheLatestTurnEvenWhenOverCharBudget() {
        String huge = "x".repeat(200);
        List<ChatMessageEntity> history = List.of(
                message(1, ChatMessageRepository.ROLE_USER, "old"),
                message(2, ChatMessageRepository.ROLE_ASSISTANT, "old-answer"),
                message(3, ChatMessageRepository.ROLE_USER, huge));

        List<com.wherelee.base.modules.contracts.llm.ChatMessage> context = builder.build(history);

        assertThat(context).hasSize(1);
        assertThat(context.get(0).content()).isEqualTo(huge);
    }

    @Test
    void skipsBlankAndNonConversationRoles() {
        List<ChatMessageEntity> history = List.of(
                message(1, ChatMessageRepository.ROLE_USER, "hello"),
                message(2, ChatMessageRepository.ROLE_ASSISTANT, "  "),
                message(3, "system", "ignored"),
                message(4, ChatMessageRepository.ROLE_ASSISTANT, "hi"));

        List<com.wherelee.base.modules.contracts.llm.ChatMessage> context = builder.build(history);

        assertThat(context).extracting(com.wherelee.base.modules.contracts.llm.ChatMessage::content)
                .containsExactly("hello", "hi");
    }

    private ChatMessageEntity message(long id, String role, String content) {
        return new ChatMessageEntity(id, 0, 1, 2, role, content, 0, 0, 0, 0,
                "req-" + id, "mock", "mock-small", "stop", ChatMessageRepository.STATUS_OK, Instant.now());
    }
}
