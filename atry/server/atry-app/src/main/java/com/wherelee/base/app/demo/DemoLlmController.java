package com.wherelee.base.app.demo;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageQuery;
import com.wherelee.base.modules.contracts.metering.UsageView;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 演示宿主：一条 curl 走完「LLM 调用 → 用量 → 计价 → 预扣/结算 → 余额」。
 *
 * <p>定位：只做串联与展示，业务逻辑（调用治理、计量计费）全部在 atry-llm / atry-metering 模块内；
 * 这里只允许依赖 contracts，不允许复制内核逻辑。租户/用户取自 JWT 登录态。</p>
 */
@RestController
@RequestMapping("/api/demo")
@ConditionalOnProperty(prefix = "atry.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoLlmController {

    private static final Logger log = LoggerFactory.getLogger(DemoLlmController.class);
    private static final long DEMO_APP_ID = 0L;
    private static final long DEADLINE_SECONDS = 30L;
    private static final long STREAM_DEADLINE_SECONDS = 300L;
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final LlmOperations llm;
    private final MeteringOperations metering;
    private final DemoBillingSupport billing;
    private final AtryDemoProperties properties;
    private final ExecutorService llmCallExecutor;
    private final ObjectMapper objectMapper;

    public DemoLlmController(LlmOperations llm, MeteringOperations metering,
                             DemoBillingSupport billing, AtryDemoProperties properties,
                             @Qualifier("llmCallThreadExecutor") ExecutorService llmCallExecutor,
                             ObjectMapper objectMapper) {
        this.llm = llm;
        this.metering = metering;
        this.billing = billing;
        this.properties = properties;
        this.llmCallExecutor = llmCallExecutor;
        this.objectMapper = objectMapper;
    }

    /** 同步调用：返回内容、用量、本次成本与结算后余额。 */
    @PostMapping("/llm/chat")
    public DemoChatResponse chat(@RequestAttribute("tenantId") long tenantId,
                                 @RequestAttribute("userId") long userId,
                                 @Valid @RequestBody DemoChatRequest body) {
        SubjectRef subject = billing.subject(tenantId, userId);
        billing.ensureAccount(subject);

        String model = body.model() == null || body.model().isBlank()
                ? properties.defaultModel() : body.model().trim();
        int maxTokens = body.maxTokens() == null ? properties.defaultMaxTokens() : body.maxTokens();
        // 36 字符：满足 t_metering_reservation.reservation_id VARCHAR(40)（"llm:" + requestId）
        String requestId = "demo" + UUID.randomUUID().toString().replace("-", "");

        long startedAt = System.currentTimeMillis();
        LlmResponse response = llm.invoke(new LlmRequest(requestId, tenantId, DEMO_APP_ID, userId, model,
                List.of(new ChatMessage("user", body.message().trim())), maxTokens, false,
                Instant.now().plusSeconds(DEADLINE_SECONDS), "demo"));
        long latencyMs = System.currentTimeMillis() - startedAt;

        LlmUsage usage = response.usage() == null
                ? new LlmUsage(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED)
                : response.usage();
        BigDecimal cost = billing.cost(usage, model, Instant.now());
        DemoChatResponse.Balance balance = billing.toView(metering.queryBalance(subject));

        log.info("demo.llm.chat requestId={} tenantId={} userId={} model={} provider={} latencyMs={} usage={} cost={}",
                requestId, tenantId, userId, model, response.provider(), latencyMs, usage.totalTokens(), cost);

        return new DemoChatResponse(requestId, response.provider(), response.modelServed(), response.content(),
                response.finishReason(), latencyMs, usage(usage), new DemoChatResponse.Money(cost, properties.currency()),
                balance);
    }

    /**
     * SSE 流式调用：`delta` 增量事件 + `done` 汇总事件（用量/成本/结算后余额）+ `error` 错误事件。
     *
     * <p>客户端断开后下一次写入会失败，回调里转为 CANCELLED 向上传播：
     * 框架取消上游生成并释放预扣（不继续计费）。</p>
     */
    @PostMapping(value = "/llm/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestAttribute("tenantId") long tenantId,
                             @RequestAttribute("userId") long userId,
                             @Valid @RequestBody DemoChatRequest body) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        llmCallExecutor.execute(() -> runStream(emitter, tenantId, userId, body));
        return emitter;
    }

    private void runStream(SseEmitter emitter, long tenantId, long userId, DemoChatRequest body) {
        SubjectRef subject = billing.subject(tenantId, userId);
        String requestId = "demo" + UUID.randomUUID().toString().replace("-", "");
        String model = body.model() == null || body.model().isBlank()
                ? properties.defaultModel() : body.model().trim();
        int maxTokens = body.maxTokens() == null ? properties.defaultMaxTokens() : body.maxTokens();
        StringBuilder content = new StringBuilder();
        AtomicReference<LlmUsage> usageRef = new AtomicReference<>();
        AtomicReference<String> finishReasonRef = new AtomicReference<>();
        try {
            billing.ensureAccount(subject);
            LlmRequest request = new LlmRequest(requestId, tenantId, DEMO_APP_ID, userId, model,
                    List.of(new ChatMessage("user", body.message().trim())), maxTokens, true,
                    Instant.now().plusSeconds(STREAM_DEADLINE_SECONDS), "demo");
            llm.stream(request, new LlmStreamSink() {
                @Override
                public void onChunk(String delta) {
                    content.append(delta);
                    send(emitter, "delta", Map.of("delta", delta));
                }

                @Override
                public void onComplete(LlmUsage usage, String finishReason) {
                    usageRef.set(usage);
                    finishReasonRef.set(finishReason);
                }

                @Override
                public void onError(LlmError error) {
                    // 错误由 LlmService 统一落库/记录后抛出，这里不重复发送
                }
            });
            LlmUsage usage = usageRef.get() == null
                    ? new LlmUsage(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED)
                    : usageRef.get();
            BigDecimal cost = billing.cost(usage, model, Instant.now());
            DemoChatResponse.Balance balance = billing.toView(metering.queryBalance(subject));
            log.info("demo.llm.stream requestId={} tenantId={} userId={} model={} usage={} cost={}",
                    requestId, tenantId, userId, model, usage.totalTokens(), cost);
            trySend(emitter, "done", new DemoChatResponse(requestId, "mock/stream", model,
                    content.toString(), finishReasonRef.get(), 0, usage(usage),
                    new DemoChatResponse.Money(cost, properties.currency()), balance));
            emitter.complete();
        } catch (LlmException e) {
            log.warn("demo.llm.stream.failed requestId={} category={} message={}",
                    requestId, e.error().category(), e.error().message());
            trySend(emitter, "error", Map.of("category", e.error().category().name(),
                    "message", e.error().message()));
            emitter.complete();
        } catch (RuntimeException e) {
            log.error("demo.llm.stream.unexpected requestId={}", requestId, e);
            trySend(emitter, "error", Map.of("message", "流式调用内部错误"));
            emitter.complete();
        }
    }

    /** 发送 SSE 事件；客户端断开时转 CANCELLED 向上传播，让框架取消上游并释放预扣。 */
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
            // 客户端已断/emitter 已结束：不再抛出
        }
    }

    /** 当前演示账户余额（首次访问自动开户并注入演示余额）。 */
    @GetMapping("/metering/balance")
    public DemoChatResponse.Balance balance(@RequestAttribute("tenantId") long tenantId,
                                            @RequestAttribute("userId") long userId) {
        SubjectRef subject = billing.subject(tenantId, userId);
        return billing.toView(billing.ensureAccount(subject));
    }

    /** 最近用量事件（来自 outbox 异步投递的计量事件，用于观察「调用后自动入账」）。 */
    @GetMapping("/metering/usage")
    public List<UsageView> usage(@RequestAttribute("tenantId") long tenantId,
                                 @RequestAttribute("userId") long userId,
                                 @RequestParam(defaultValue = "20") int limit) {
        SubjectRef subject = billing.subject(tenantId, userId);
        return metering.queryUsage(new UsageQuery(subject,
                Instant.now().minusSeconds(3600), Instant.now(), limit));
    }

    private DemoChatResponse.Usage usage(LlmUsage usage) {
        return new DemoChatResponse.Usage(usage.inputTokens(), usage.outputTokens(), usage.cachedReadTokens(),
                usage.cachedWriteTokens(), usage.reasoningTokens(), usage.totalTokens(), usage.source().name());
    }
}
