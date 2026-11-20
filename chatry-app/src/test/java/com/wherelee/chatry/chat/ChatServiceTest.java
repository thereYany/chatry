package com.wherelee.chatry.chat;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.chatry.credits.ChatBalanceView;
import com.wherelee.chatry.credits.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final long TENANT = 1L;
    private static final long USER = 2L;
    private static final long CONVERSATION = 10L;

    @Mock
    private ChatConversationRepository conversations;
    @Mock
    private ChatMessageRepository messages;
    @Mock
    private ChatContextBuilder contextBuilder;
    @Mock
    private CreditService credits;
    @Mock
    private LlmOperations llm;

    private ChatService service;

    @BeforeEach
    void setUp() {
        ChatProperties properties = new ChatProperties(
                "mock-small", 256, 20, 12_000, 30, 120, 60_000L, 20);
        service = new ChatService(conversations, messages, contextBuilder, credits, llm, properties);
    }

    @Test
    void chatPersistsUserAndAssistantMessagesWithCost() {
        when(conversations.findOwned(TENANT, USER, CONVERSATION)).thenReturn(Optional.of(conversation()));
        when(messages.insertUserMessage(TENANT, CONVERSATION, USER, "你好")).thenReturn(1L);
        when(messages.recent(TENANT, CONVERSATION, 20)).thenReturn(List.of());
        when(contextBuilder.build(anyList())).thenReturn(List.of(new ChatMessage("user", "你好")));
        LlmUsage usage = new LlmUsage(3, 80, 0, 0, 0, LlmUsage.Source.PROVIDER);
        when(llm.invoke(any())).thenReturn(new LlmResponse("req-1", "mock", "mock-small",
                "mock reply", "stop", usage, false));
        when(credits.cost(any(), eq("mock-small"), any(Instant.class)))
                .thenReturn(new BigDecimal("0.000083"));
        when(credits.balance(TENANT, USER))
                .thenReturn(new ChatBalanceView(new BigDecimal("4.999917"), BigDecimal.ZERO, "CNY"));
        when(messages.insertAssistantMessage(eq(TENANT), eq(CONVERSATION), eq(USER), anyString(),
                anyInt(), anyInt(), anyInt(), anyLong(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(2L);

        ChatResult result = service.chat(TENANT, USER, CONVERSATION, "你好", null);

        assertThat(result.messageId()).isEqualTo(2L);
        assertThat(result.content()).isEqualTo("mock reply");
        assertThat(result.cost()).isEqualByComparingTo("0.000083");
        assertThat(result.balance().balance()).isEqualByComparingTo("4.999917");

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> costMicros = ArgumentCaptor.forClass(Long.class);
        verify(messages).insertAssistantMessage(eq(TENANT), eq(CONVERSATION), eq(USER), eq("mock reply"),
                eq(3), eq(80), eq(0), costMicros.capture(), anyString(), eq("mock"), eq("mock-small"),
                eq("stop"), status.capture());
        assertThat(costMicros.getValue()).isEqualTo(83L);
        assertThat(status.getValue()).isEqualTo(ChatMessageRepository.STATUS_OK);
        verify(conversations).refresh(eq(TENANT), eq(CONVERSATION), any(Instant.class));
    }

    @Test
    void cancelledStreamPersistsPartialContentWithoutCost() {
        when(conversations.findOwned(TENANT, USER, CONVERSATION)).thenReturn(Optional.of(conversation()));
        when(messages.insertUserMessage(TENANT, CONVERSATION, USER, "你好")).thenReturn(1L);
        when(messages.recent(TENANT, CONVERSATION, 20)).thenReturn(List.of());
        when(contextBuilder.build(anyList())).thenReturn(List.of(new ChatMessage("user", "你好")));
        doAnswer(invocation -> {
            LlmStreamSink sink = invocation.getArgument(1);
            sink.onChunk("partial-");
            throw new LlmException(new LlmError(LlmErrorCategory.CANCELLED, null, false, false,
                    "客户端已断开"));
        }).when(llm).stream(any(), any());

        assertThatThrownBy(() -> service.stream(TENANT, USER, CONVERSATION, "你好", null, delta -> {
        })).isInstanceOf(LlmException.class);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> costMicros = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(messages).insertAssistantMessage(eq(TENANT), eq(CONVERSATION), eq(USER), content.capture(),
                eq(0), eq(0), eq(0), costMicros.capture(), anyString(), any(), eq("mock-small"),
                eq("CANCELLED"), status.capture());
        assertThat(content.getValue()).isEqualTo("partial-");
        assertThat(costMicros.getValue()).isZero();
        assertThat(status.getValue()).isEqualTo(ChatMessageRepository.STATUS_CANCELLED);
        verify(conversations).refresh(eq(TENANT), eq(CONVERSATION), any(Instant.class));
    }

    @Test
    void rejectsBlankMessageBeforeCallingLlm() {
        when(conversations.findOwned(TENANT, USER, CONVERSATION)).thenReturn(Optional.of(conversation()));

        assertThatThrownBy(() -> service.chat(TENANT, USER, CONVERSATION, "   ", null))
                .isInstanceOf(com.wherelee.base.modules.platform.common.BizException.class)
                .hasMessageContaining("消息不能为空");
    }

    private ChatConversation conversation() {
        Instant now = Instant.now();
        return new ChatConversation(CONVERSATION, TENANT, USER, "新对话", "mock-small", "ACTIVE",
                0, null, now, now);
    }
}
