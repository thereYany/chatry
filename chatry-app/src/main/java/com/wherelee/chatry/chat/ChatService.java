package com.wherelee.chatry.chat;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.chatry.credits.ChatBalanceView;
import com.wherelee.chatry.credits.CreditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 对话业务：会话编排 + 上下文拼装 + 调用内核 + 落库。
 *
 * <p>不复制内核逻辑：超时/重试/熔断/限流/预扣/结算全在 atry-llm 内部完成，
 * 这里只负责「用户说了什么、回答了什么、花了多少」。</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final long APP_ID = 0L;
    private static final String BUDGET_REF = "chat";
    private static final String DEFAULT_TITLE = "新对话";
    private static final int MAX_CONTENT_CHARS = 8_000;
    private static final int LIST_LIMIT = 50;
    private static final int HISTORY_LIMIT = 200;

    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatContextBuilder contextBuilder;
    private final CreditService credits;
    private final LlmOperations llm;
    private final ChatProperties properties;

    public ChatService(ChatConversationRepository conversations, ChatMessageRepository messages,
                       ChatContextBuilder contextBuilder, CreditService credits,
                       LlmOperations llm, ChatProperties properties) {
        this.conversations = conversations;
        this.messages = messages;
        this.contextBuilder = contextBuilder;
        this.credits = credits;
        this.llm = llm;
        this.properties = properties;
    }

    public ConversationView create(long tenantId, long userId, String title) {
        String resolved = title == null || title.isBlank() ? DEFAULT_TITLE : title.trim();
        if (resolved.length() > 120) {
            resolved = resolved.substring(0, 120);
        }
        long id = conversations.create(tenantId, userId, resolved, properties.defaultModel());
        return ConversationView.from(conversations.findOwned(tenantId, userId, id)
                .orElseThrow(() -> new IllegalStateException("会话创建后读取失败")));
    }

    public List<ConversationView> list(long tenantId, long userId) {
        return conversations.listByUser(tenantId, userId, LIST_LIMIT).stream()
                .map(ConversationView::from)
                .toList();
    }

    public ConversationView get(long tenantId, long userId, long conversationId) {
        return ConversationView.from(requireConversation(tenantId, userId, conversationId));
    }

    public List<ChatMessageView> messages(long tenantId, long userId, long conversationId) {
        requireConversation(tenantId, userId, conversationId);
        return messages.listByConversation(tenantId, conversationId, HISTORY_LIMIT).stream()
                .map(ChatMessageView::from)
                .toList();
    }

    public void delete(long tenantId, long userId, long conversationId) {
        if (!conversations.softDelete(tenantId, userId, conversationId)) {
            throw BizException.notFound("会话不存在");
        }
    }

    /** 非流式对话：一次返回完整回复与结算结果。 */
    public ChatResult chat(long tenantId, long userId, long conversationId,
                           String content, String modelParam) {
        Prepared prepared = prepare(tenantId, userId, conversationId, content, modelParam);
        long startedAt = System.currentTimeMillis();
        LlmResponse response = llm.invoke(new LlmRequest(prepared.requestId(), tenantId, APP_ID, userId,
                prepared.model(), prepared.context(), properties.defaultMaxTokens(), false,
                Instant.now().plusSeconds(properties.deadlineSeconds()), BUDGET_REF));
        LlmUsage usage = normalise(response.usage());
        BigDecimal cost = credits.cost(usage, prepared.model(), Instant.now());
        long assistantMessageId = messages.insertAssistantMessage(tenantId, conversationId, userId,
                response.content(), (int) usage.inputTokens(), (int) usage.outputTokens(),
                (int) usage.cachedReadTokens(), CreditService.micros(cost), prepared.requestId(),
                response.provider(), response.modelServed(), response.finishReason(),
                ChatMessageRepository.STATUS_OK);
        conversations.refresh(tenantId, conversationId, Instant.now());
        ChatBalanceView balance = credits.balance(tenantId, userId);
        long latencyMs = System.currentTimeMillis() - startedAt;
        log.info("chat.reply tenantId={} userId={} conversationId={} requestId={} provider={} model={} "
                        + "latencyMs={} usage={} cost={} balance={}",
                tenantId, userId, conversationId, prepared.requestId(), response.provider(), prepared.model(),
                latencyMs, usage.totalTokens(), cost, balance.balance());
        return new ChatResult(assistantMessageId, response.content(), ChatUsageView.from(usage), cost,
                balance.currency(), balance, response.provider(), prepared.model(),
                response.finishReason(), latencyMs);
    }

    /**
     * 流式对话：每个增量回调给 {@code onDelta}；结束后落库并返回汇总结果。
     *
     * <p>客户端断开时 {@code onDelta} 抛 CANCELLED，本方法把已生成的部分内容按
     * CANCELLED 落库（不计费），随后把异常继续抛给调用方。</p>
     */
    public ChatResult stream(long tenantId, long userId, long conversationId,
                             String content, String modelParam, Consumer<String> onDelta) {
        Prepared prepared = prepare(tenantId, userId, conversationId, content, modelParam);
        StringBuilder generated = new StringBuilder();
        AtomicReference<LlmUsage> usageRef = new AtomicReference<>();
        AtomicReference<String> finishRef = new AtomicReference<>();
        long startedAt = System.currentTimeMillis();
        try {
            llm.stream(new LlmRequest(prepared.requestId(), tenantId, APP_ID, userId, prepared.model(),
                    prepared.context(), properties.defaultMaxTokens(), true,
                    Instant.now().plusSeconds(properties.streamDeadlineSeconds()), BUDGET_REF),
                    new LlmStreamSink() {
                        @Override
                        public void onChunk(String delta) {
                            generated.append(delta);
                            onDelta.accept(delta);
                        }

                        @Override
                        public void onComplete(LlmUsage usage, String finishReason) {
                            usageRef.set(usage);
                            finishRef.set(finishReason);
                        }

                        @Override
                        public void onError(LlmError error) {
                            // 失败由 atry-llm 统一记录并抛 LlmException，这里不重复处理
                        }
                    });
        } catch (LlmException e) {
            persistPartial(tenantId, conversationId, userId, prepared, generated.toString(), e);
            throw e;
        }

        LlmUsage usage = normalise(usageRef.get());
        BigDecimal cost = credits.cost(usage, prepared.model(), Instant.now());
        long assistantMessageId = messages.insertAssistantMessage(tenantId, conversationId, userId,
                generated.toString(), (int) usage.inputTokens(), (int) usage.outputTokens(),
                (int) usage.cachedReadTokens(), CreditService.micros(cost), prepared.requestId(),
                null, prepared.model(), finishRef.get(), ChatMessageRepository.STATUS_OK);
        conversations.refresh(tenantId, conversationId, Instant.now());
        ChatBalanceView balance = credits.balance(tenantId, userId);
        long latencyMs = System.currentTimeMillis() - startedAt;
        log.info("chat.stream.reply tenantId={} userId={} conversationId={} requestId={} model={} "
                        + "latencyMs={} usage={} cost={} balance={}",
                tenantId, userId, conversationId, prepared.requestId(), prepared.model(),
                latencyMs, usage.totalTokens(), cost, balance.balance());
        return new ChatResult(assistantMessageId, generated.toString(), ChatUsageView.from(usage), cost,
                balance.currency(), balance, null, prepared.model(), finishRef.get(), latencyMs);
    }

    // ---------- 内部 ----------

    private Prepared prepare(long tenantId, long userId, long conversationId,
                             String content, String modelParam) {
        ChatConversation conversation = requireConversation(tenantId, userId, conversationId);
        String text = requireContent(content);
        String model = resolveModel(modelParam, conversation);
        credits.ensureAccount(tenantId, userId);

        messages.insertUserMessage(tenantId, conversationId, userId, text);
        conversations.renameIfPlaceholder(tenantId, conversationId, DEFAULT_TITLE, titleOf(text));

        List<ChatMessage> context = contextBuilder.build(
                messages.recent(tenantId, conversationId, properties.contextMessages()));
        String requestId = "chat" + UUID.randomUUID().toString().replace("-", "");
        return new Prepared(requestId, model, context);
    }

    private ChatConversation requireConversation(long tenantId, long userId, long conversationId) {
        return conversations.findOwned(tenantId, userId, conversationId)
                .filter(conversation -> "ACTIVE".equals(conversation.status()))
                .orElseThrow(() -> BizException.notFound("会话不存在"));
    }

    private String resolveModel(String modelParam, ChatConversation conversation) {
        if (modelParam != null && !modelParam.isBlank()) {
            return modelParam.trim();
        }
        if (conversation.model() != null && !conversation.model().isBlank()) {
            return conversation.model();
        }
        return properties.defaultModel();
    }

    private String requireContent(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw BizException.badRequest("消息不能为空");
        }
        if (text.length() > MAX_CONTENT_CHARS) {
            throw BizException.badRequest("消息过长（上限 " + MAX_CONTENT_CHARS + " 字符）");
        }
        return text;
    }

    private String titleOf(String content) {
        String oneLine = content.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= properties.titleChars()
                ? oneLine
                : oneLine.substring(0, properties.titleChars());
    }

    private void persistPartial(long tenantId, long conversationId, long userId,
                                Prepared prepared, String partial, LlmException error) {
        String status = error.error().category() == LlmErrorCategory.CANCELLED
                ? ChatMessageRepository.STATUS_CANCELLED
                : ChatMessageRepository.STATUS_FAILED;
        if (partial == null || partial.isBlank()) {
            return;
        }
        messages.insertAssistantMessage(tenantId, conversationId, userId, partial, 0, 0, 0, 0,
                prepared.requestId(), null, prepared.model(), error.error().category().name(), status);
        conversations.refresh(tenantId, conversationId, Instant.now());
        log.info("chat.partial.saved tenantId={} conversationId={} status={} chars={}",
                tenantId, conversationId, status, partial.length());
    }

    private LlmUsage normalise(LlmUsage usage) {
        return usage == null ? new LlmUsage(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED) : usage;
    }

    private record Prepared(String requestId, String model, List<ChatMessage> context) {
        private Prepared {
        }
    }
}
