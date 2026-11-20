package com.wherelee.chatry.chat.web;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.chatry.chat.ChatMessageView;
import com.wherelee.chatry.chat.ChatProperties;
import com.wherelee.chatry.chat.ChatResult;
import com.wherelee.chatry.chat.ChatService;
import com.wherelee.chatry.chat.ConversationView;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 对话接口：会话管理 + 同步/流式对话。
 *
 * <p>只做参数与传输层，业务编排在 {@link ChatService}；身份/租户来自 JwtAuthFilter
 * 写入的 request attribute，不接受客户端自报的 tenantId/userId。</p>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chat;
    private final ChatProperties properties;
    private final ExecutorService llmCallThreadExecutor;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chat, ChatProperties properties,
                          ExecutorService llmCallThreadExecutor, ObjectMapper objectMapper) {
        this.chat = chat;
        this.properties = properties;
        this.llmCallThreadExecutor = llmCallThreadExecutor;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/conversations")
    public ConversationView create(@RequestAttribute("tenantId") long tenantId,
                                   @RequestAttribute("userId") long userId,
                                   @RequestBody(required = false) CreateConversationRequest request) {
        return chat.create(tenantId, userId, request == null ? null : request.title());
    }

    @GetMapping("/conversations")
    public List<ConversationView> list(@RequestAttribute("tenantId") long tenantId,
                                       @RequestAttribute("userId") long userId) {
        return chat.list(tenantId, userId);
    }

    @GetMapping("/conversations/{id}")
    public ConversationView get(@RequestAttribute("tenantId") long tenantId,
                                @RequestAttribute("userId") long userId,
                                @PathVariable long id) {
        return chat.get(tenantId, userId, id);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<ChatMessageView> messages(@RequestAttribute("tenantId") long tenantId,
                                          @RequestAttribute("userId") long userId,
                                          @PathVariable long id) {
        return chat.messages(tenantId, userId, id);
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> delete(@RequestAttribute("tenantId") long tenantId,
                                       @RequestAttribute("userId") long userId,
                                       @PathVariable long id) {
        chat.delete(tenantId, userId, id);
        return ResponseEntity.noContent().build();
    }

    /** 同步对话：一次拿到完整回复、用量、成本与结算后余额。 */
    @PostMapping("/conversations/{id}/messages")
    public ChatResult send(@RequestAttribute("tenantId") long tenantId,
                           @RequestAttribute("userId") long userId,
                           @PathVariable long id,
                           @Valid @RequestBody ChatRequest request) {
        return chat.chat(tenantId, userId, id, request.content(), request.model());
    }

    /**
     * 流式对话（SSE）：delta 增量 + done 汇总 + error 错误事件。
     *
     * <p>客户端断开后下一次写入失败，回调抛 CANCELLED：框架取消上游生成并释放预扣，
     * 已生成的部分按 CANCELLED 落库、不计费。</p>
     */
    @PostMapping(value = "/conversations/{id}/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestAttribute("tenantId") long tenantId,
                             @RequestAttribute("userId") long userId,
                             @PathVariable long id,
                             @Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(properties.streamTimeoutMs());
        llmCallThreadExecutor.execute(() -> runStream(emitter, tenantId, userId, id, request));
        return emitter;
    }

    private void runStream(SseEmitter emitter, long tenantId, long userId, long conversationId,
                           ChatRequest request) {
        try {
            ChatResult result = chat.stream(tenantId, userId, conversationId, request.content(),
                    request.model(), delta -> send(emitter, "delta", Map.of("delta", delta)));
            trySend(emitter, "done", result);
            emitter.complete();
        } catch (LlmException e) {
            log.warn("chat.stream.failed tenantId={} userId={} conversationId={} category={} message={}",
                    tenantId, userId, conversationId, e.error().category(), e.error().message());
            trySend(emitter, "error", Map.of("category", e.error().category().name(),
                    "message", e.error().message()));
            emitter.complete();
        } catch (BizException e) {
            trySend(emitter, "error", Map.of("category", "INVALID_REQUEST", "message", e.getMessage()));
            emitter.complete();
        } catch (RuntimeException e) {
            log.error("chat.stream.unexpected tenantId={} conversationId={}", tenantId, conversationId, e);
            trySend(emitter, "error", Map.of("category", "INTERNAL", "message", "对话服务内部错误"));
            emitter.complete();
        }
    }

    /** 发送 SSE 事件；客户端断开时转 CANCELLED 向上传播，让内核取消上游并释放预扣。 */
    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new LlmException(new LlmError(LlmErrorCategory.CANCELLED, null, false, false,
                    "客户端已断开：" + e.getClass().getSimpleName()));
        }
    }

    private void trySend(SseEmitter emitter, String event, Object data) {
        try {
            send(emitter, event, data);
        } catch (RuntimeException ignored) {
            // 客户端已断开 / emitter 已结束：不再抛出
        }
    }
}
