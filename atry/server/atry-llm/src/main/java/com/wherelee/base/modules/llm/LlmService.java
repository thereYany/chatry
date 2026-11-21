package com.wherelee.base.modules.llm;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmCallCompleted;
import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.ReleaseCommand;
import com.wherelee.base.modules.contracts.metering.ReserveCommand;
import com.wherelee.base.modules.contracts.metering.SettleCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.llm.call.LlmCallRecord;
import com.wherelee.base.modules.llm.call.LlmCallRepository;
import com.wherelee.base.modules.llm.call.LlmCallWriter;
import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.execution.LlmCallExecutor;
import com.wherelee.base.modules.llm.execution.LlmStreamExecutor;
import com.wherelee.base.modules.llm.execution.StreamOutcome;
import com.wherelee.base.modules.llm.provider.LlmProvider;
import com.wherelee.base.modules.llm.provider.LlmProviderRegistry;
import com.wherelee.base.modules.llm.resilience.LlmCircuitBreaker;
import com.wherelee.base.modules.llm.resilience.LlmRateLimiter;
import com.wherelee.base.modules.llm.resilience.LlmRetryBudget;
import com.wherelee.base.modules.llm.resilience.LlmRetryPolicy;
import com.wherelee.base.modules.llm.observability.LlmEventLogger;
import com.wherelee.base.modules.llm.observability.LlmMetrics;
import com.wherelee.base.modules.llm.observability.LlmSpanTags;
import com.wherelee.base.modules.platform.tracing.TraceSpans;
import com.wherelee.base.modules.platform.common.BizException;
import io.micrometer.tracing.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * LLM 调用编排（P2）：校验 → 限流/舱壁 → 熔断 → 单点重试（带预算/退避）→ 调用记录 + outbox + 日志。
 *
 * <p>provider 只负责协议调用；超时、重试、熔断、限流全部在此层，避免重试叠加。
 * 日志只含元数据与 usage，不含 prompt/response 内容。</p>
 */
@Service
public class LlmService implements LlmOperations {

    private final LlmProviderRegistry providerRegistry;
    private final LlmCallRepository callRepository;
    private final LlmCallWriter callWriter;
    private final TraceSpans traceSpans;
    private final LlmCallExecutor executor;
    private final LlmStreamExecutor streamExecutor;
    private final LlmRetryPolicy retryPolicy;
    private final LlmRetryBudget retryBudget;
    private final LlmCircuitBreaker circuitBreaker;
    private final LlmRateLimiter rateLimiter;
    private final LlmProperties.Timeouts timeouts;
    private final LlmEventLogger events;
    private final LlmMetrics metrics;
    private final MeteringOperations metering;

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final String BILLING_METER = "llm.tokens";
    private static final String INPUT_METER = "llm.input_tokens";
    private static final String OUTPUT_METER = "llm.output_tokens";
    private static final String CACHED_READ_METER = "llm.cached_read_tokens";
    private static final String BILLING_CURRENCY = "CNY";

    public LlmService(LlmProviderRegistry providerRegistry, LlmCallRepository callRepository,
                      LlmCallWriter callWriter, TraceSpans traceSpans, LlmCallExecutor executor,
                      LlmStreamExecutor streamExecutor,
                      LlmRetryPolicy retryPolicy, LlmRetryBudget retryBudget,
                      LlmCircuitBreaker circuitBreaker, LlmRateLimiter rateLimiter,
                      LlmProperties properties, LlmEventLogger events, LlmMetrics metrics,
                      MeteringOperations metering) {
        this.providerRegistry = providerRegistry;
        this.callRepository = callRepository;
        this.callWriter = callWriter;
        this.traceSpans = traceSpans;
        this.executor = executor;
        this.streamExecutor = streamExecutor;
        this.retryPolicy = retryPolicy;
        this.retryBudget = retryBudget;
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
        this.timeouts = properties.timeouts();
        this.events = events;
        this.metrics = metrics;
        this.metering = metering;
    }

    @Override
    public LlmResponse invoke(LlmRequest request) {
        validate(request);
        if (callRepository.existsByRequestId(request.requestId())) {
            throw new LlmException(new LlmError(LlmErrorCategory.INVALID_REQUEST, null, false, false,
                    "重复的 requestId：" + request.requestId()));
        }
        LlmProvider provider = providerRegistry.resolve(request.model());
        String circuitKey = provider.id() + ":" + request.model();
        long startedAt = System.currentTimeMillis();
        long deadlineAt = deadlineMs(request);
        int attempts = 0;
        String permit = null;
        String reservationId = null;
        boolean settled = false;
        events.started(request, provider.id());
        try {
            reservationId = reserveBudget(request);
            permit = rateLimiter.acquire(provider.id(), request.tenantId());
            circuitBreaker.acquire(circuitKey);
            LlmException last = null;
            int maxAttempts = retryPolicy.maxAttempts();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                attempts = attempt;
                long remaining = deadlineAt - System.currentTimeMillis();
                if (remaining <= 0) {
                    last = timeoutError("deadline 已耗尽");
                    break;
                }
                long attemptTimeout = Math.min(Math.min(timeouts.firstTokenMs(), timeouts.totalMs()), remaining);
                try {
                    LlmResponse response = traceSpans.trace("llm.invoke",
                            TraceSpans.tags("llm.request_id", request.requestId(),
                                    "llm.provider", provider.id(),
                                    "llm.model", request.model(),
                                    "llm.attempt", Integer.toString(attempt)),
                            span -> invokeWithSpan(span, provider, request, attemptTimeout));
                    long latencyMs = System.currentTimeMillis() - startedAt;
                    LlmUsage usage = response.usage() == null
                            ? new LlmUsage(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED)
                            : response.usage();
                    circuitBreaker.recordSuccess(circuitKey, latencyMs);
                    retryBudget.onSuccess(provider.id());
                    callWriter.writeSuccess(
                            successRecord(request, provider.id(), response, usage, latencyMs, attempts, false),
                            new LlmCallCompleted(request.requestId(), request.tenantId(), request.appId(),
                                    request.userId(), provider.id(), response.modelServed(), usage,
                                    latencyMs, Instant.now()));
                    BigDecimal actual = actualCostOrNull(request, usage);
                    settleBudget(reservationId, request.requestId(), actual);
                    settled = true;
                    BigDecimal cost = actual == null ? BigDecimal.ZERO : actual;
                    events.finished(request, provider.id(), response, usage, latencyMs, attempts, false, cost);
                    metrics.success(provider.id(), request.model(), false, latencyMs, usage, null);
                    metrics.cost(request.tenantId(), request.model(), cost);
                    return response;
                } catch (LlmException e) {
                    last = e;
                    circuitBreaker.recordFailure(circuitKey, e.error());
                    long backoff = retryPolicy.backoffMs(attempt);
                    boolean canRetry = attempt < maxAttempts
                            && retryPolicy.retryable(e.error())
                            && !circuitBreaker.isOpen(circuitKey)
                            && retryBudget.tryRetry(provider.id())
                            && System.currentTimeMillis() + backoff < deadlineAt;
                    events.attemptFailed(request, provider.id(), e.error(), attempt, canRetry,
                            canRetry ? backoff : 0);
                    if (canRetry) {
                        metrics.retry(provider.id(), true);
                    }
                    if (!canRetry) {
                        break;
                    }
                    sleepBackoff(backoff);
                }
            }
            throw last == null ? new LlmException(new LlmError(LlmErrorCategory.INTERNAL, "NO_ATTEMPT",
                    false, false, "没有可执行的尝试")) : last;
        } catch (LlmException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            callWriter.writeFailure(failureRecord(request, provider.id(), e.error(), latencyMs, attempts));
            events.failed(request, provider.id(), e.error(), latencyMs, attempts, false, BigDecimal.ZERO);
            metrics.failure(provider.id(), request.model(), false, e.error(), latencyMs);
            throw e;
        } catch (RuntimeException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            LlmError error = new LlmError(LlmErrorCategory.INTERNAL, "UNEXPECTED", false, false,
                    "LLM 调用内部错误：" + e.getClass().getSimpleName());
            callWriter.writeFailure(failureRecord(request, provider.id(), error, latencyMs, attempts));
            events.failed(request, provider.id(), error, latencyMs, attempts, false, BigDecimal.ZERO);
            metrics.failure(provider.id(), request.model(), false, error, latencyMs);
            throw new LlmException(error, e);
        } finally {
            if (permit != null) {
                rateLimiter.release(permit);
            }
            if (reservationId != null && !settled) {
                releaseBudget(reservationId);
            }
        }
    }

    @Override
    public void stream(LlmRequest request, LlmStreamSink sink) {
        validate(request);
        if (callRepository.existsByRequestId(request.requestId())) {
            throw new LlmException(new LlmError(LlmErrorCategory.INVALID_REQUEST, null, false, false,
                    "重复的 requestId：" + request.requestId()));
        }
        LlmProvider provider = providerRegistry.resolve(request.model());
        String circuitKey = provider.id() + ":" + request.model();
        long startedAt = System.currentTimeMillis();
        long deadlineAt = deadlineMs(request);
        String permit = null;
        boolean persisted = false;
        String reservationId = null;
        boolean settled = false;
        events.started(request, provider.id());
        try {
            reservationId = reserveBudget(request);
            permit = rateLimiter.acquire(provider.id(), request.tenantId());
            circuitBreaker.acquire(circuitKey);
            StreamOutcome outcome = traceSpans.trace("llm.invoke",
                    TraceSpans.tags("llm.request_id", request.requestId(),
                            "llm.provider", provider.id(),
                            "llm.model", request.model(),
                            "llm.stream", "true"),
                    span -> streamWithSpan(span, provider, request, sink, deadlineAt));
            long latencyMs = System.currentTimeMillis() - startedAt;
            if (outcome.completed()) {
                LlmUsage usage = outcome.usage() == null
                        ? new LlmUsage(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED)
                        : outcome.usage();
                circuitBreaker.recordSuccess(circuitKey, latencyMs);
                retryBudget.onSuccess(provider.id());
                callWriter.writeSuccess(
                        successRecord(request, provider.id(), responseOf(outcome, request.model()), usage,
                                latencyMs, 1, true),
                        new LlmCallCompleted(request.requestId(), request.tenantId(), request.appId(),
                                request.userId(), provider.id(), request.model(), usage, latencyMs, Instant.now()));
                persisted = true;
                BigDecimal streamCost = pricedCostOrZero(request, usage);
                settleBudget(reservationId, request.requestId(), streamCost);
                settled = true;
                events.finished(request, provider.id(), responseOf(outcome, request.model()), usage,
                        latencyMs, 1, true, streamCost);
                metrics.success(provider.id(), request.model(), true, latencyMs, usage, outcome.ttftMs());
                metrics.cost(request.tenantId(), request.model(), streamCost);
                return;
            }
            LlmError error = outcome.error();
            circuitBreaker.recordFailure(circuitKey, error);
            String status = error.category() == LlmErrorCategory.CANCELLED ? "CANCELLED"
                    : error.category() == LlmErrorCategory.STREAM_INTERRUPTED ? "INTERRUPTED" : "FAILED";
            callWriter.writeFailure(failureRecord(request, provider.id(), error, latencyMs, 1,
                    outcome.usage(), status));
            persisted = true;
            BigDecimal partialCost = pricedCostOrZero(request, outcome.usage());
            if (partialCost.signum() > 0 && request.budgetRef() != null && !request.budgetRef().isBlank()) {
                // 已输出部分内容：按部分 usage 结算（设计 04 §8：不可续写，但已产生的 token 计费）
                settleBudget(reservationId, request.requestId(), partialCost);
                settled = true;
            }
            events.failed(request, provider.id(), error, latencyMs, 1, true, partialCost);
            metrics.failure(provider.id(), request.model(), true, error, latencyMs);
            throw new LlmException(error);
        } catch (LlmException e) {
            if (!persisted) {
                long latencyMs = System.currentTimeMillis() - startedAt;
                callWriter.writeFailure(failureRecord(request, provider.id(), e.error(), latencyMs, 0));
                events.failed(request, provider.id(), e.error(), latencyMs, 0, true, BigDecimal.ZERO);
                metrics.failure(provider.id(), request.model(), true, e.error(), latencyMs);
            }
            throw e;
        } catch (RuntimeException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            LlmError error = new LlmError(LlmErrorCategory.INTERNAL, "UNEXPECTED", false, false,
                    "流式调用内部错误：" + e.getClass().getSimpleName());
            if (!persisted) {
                callWriter.writeFailure(failureRecord(request, provider.id(), error, latencyMs, 0));
            }
            events.failed(request, provider.id(), error, latencyMs, 0, true, BigDecimal.ZERO);
            metrics.failure(provider.id(), request.model(), true, error, latencyMs);
            throw new LlmException(error, e);
        } finally {
            if (permit != null) {
                rateLimiter.release(permit);
            }
            if (reservationId != null && !settled) {
                releaseBudget(reservationId);
            }
        }
    }

    private String reserveBudget(LlmRequest request) {
        if (request.budgetRef() == null || request.budgetRef().isBlank()) {
            return null;
        }
        try {
            BigDecimal amount = estimateBudgetCost(request);
            if (amount.signum() <= 0) {
                return null;
            }
            String reservationId = "llm:" + request.requestId();
            metering.reserve(new ReserveCommand(reservationId, subjectOf(request), amount, BILLING_CURRENCY,
                    Instant.now().plusMillis(timeouts.totalMs()), request.requestId()));
            return reservationId;
        } catch (BizException e) {
            LlmError error = new LlmError(LlmErrorCategory.QUOTA_EXHAUSTED, null, false, false,
                    "预算预扣失败：" + e.getMessage());
            throw new LlmException(error, e);
        }
    }

    private void settleBudget(String reservationId, String requestId, BigDecimal actual) {
        if (reservationId == null) {
            return;
        }
        if (actual == null) {
            // 价格不可用时不能按 0 结算（会释放预扣但不计费），留给过期回收/对账兜底
            log.warn("llm.billing.settle_skipped requestId={} reservationId={} reason=price_unavailable",
                    requestId, reservationId);
            return;
        }
        try {
            metering.settle(new SettleCommand(reservationId, actual, BILLING_CURRENCY, requestId));
        } catch (RuntimeException e) {
            log.warn("llm.billing.settle_failed requestId={} reservationId={} error={}",
                    requestId, reservationId, e.getClass().getSimpleName());
        }
    }

    private void releaseBudget(String reservationId) {
        try {
            metering.release(new ReleaseCommand(reservationId, "llm call failed"));
        } catch (RuntimeException e) {
            log.warn("llm.billing.release_failed reservationId={} error={}",
                    reservationId, e.getClass().getSimpleName());
        }
    }

    /** 结算/成本计算；价格缺失返回 null（不按 0 计费），由 settleBudget 决定跳过结算。 */
    private BigDecimal actualCostOrNull(LlmRequest request, LlmUsage usage) {
        try {
            return actualCost(request, usage);
        } catch (RuntimeException e) {
            log.warn("llm.billing.price_unavailable requestId={} model={} error={}",
                    request.requestId(), request.model(), e.getClass().getSimpleName());
            return null;
        }
    }

    /** 观测用成本：价格缺失时退化为 0，不阻塞调用结果返回。 */
    private BigDecimal pricedCostOrZero(LlmRequest request, LlmUsage usage) {
        if (usage == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal cost = actualCostOrNull(request, usage);
        return cost == null ? BigDecimal.ZERO : cost;
    }

    private BigDecimal estimateBudgetCost(LlmRequest request) {
        long input = estimateInputTokens(request);
        long output = request.maxTokens() == null ? 128 : request.maxTokens();
        Map<String, String> dimensions = Map.of("model", request.model());
        try {
            BigDecimal inputCost = metering.estimateCost(INPUT_METER, BigDecimal.valueOf(input),
                    Instant.now(), dimensions);
            BigDecimal outputCost = metering.estimateCost(OUTPUT_METER, BigDecimal.valueOf(output),
                    Instant.now(), dimensions);
            return inputCost.add(outputCost);
        } catch (BizException e) {
            // 未配置 token 类型粒度价格时退回总量口径，保证预扣不阻塞调用
            return metering.estimateCost(BILLING_METER, BigDecimal.valueOf(input + output), Instant.now());
        }
    }

    private BigDecimal actualCost(LlmRequest request, LlmUsage usage) {
        long nonCachedInput = Math.max(0, usage.inputTokens() - usage.cachedReadTokens());
        Map<String, String> dimensions = Map.of("model", request.model());
        try {
            return metering.estimateCost(INPUT_METER, BigDecimal.valueOf(nonCachedInput), Instant.now(), dimensions)
                    .add(metering.estimateCost(OUTPUT_METER, BigDecimal.valueOf(usage.outputTokens()),
                            Instant.now(), dimensions))
                    .add(metering.estimateCost(CACHED_READ_METER,
                            BigDecimal.valueOf(usage.cachedReadTokens()), Instant.now(), dimensions));
        } catch (BizException e) {
            return metering.estimateCost(BILLING_METER,
                    BigDecimal.valueOf(Math.max(1, usage.totalTokens())), Instant.now());
        }
    }

    private long estimateInputTokens(LlmRequest request) {
        int chars = request.messages().stream()
                .mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum();
        return Math.max(1, (chars + 3) / 4);
    }

    private SubjectRef subjectOf(LlmRequest request) {
        return new SubjectRef(request.tenantId(), request.appId(), request.userId());
    }

    private LlmResponse invokeWithSpan(Span span, LlmProvider provider, LlmRequest request, long timeoutMs) {
        LlmSpanTags.request(span, request, provider.id());
        try {
            LlmResponse response = executor.invoke(provider, request, timeoutMs);
            LlmSpanTags.response(span, response, response.usage());
            return response;
        } catch (LlmException e) {
            LlmSpanTags.error(span, e.error());
            throw e;
        }
    }

    private StreamOutcome streamWithSpan(Span span, LlmProvider provider, LlmRequest request,
                                         LlmStreamSink sink, long deadlineAt) {
        LlmSpanTags.request(span, request, provider.id());
        StreamOutcome outcome = streamExecutor.stream(provider, request, sink,
                timeouts.firstTokenMs(), timeouts.chunkIdleMs(), timeouts.totalMs(), deadlineAt);
        if (outcome.completed()) {
            LlmSpanTags.response(span, null, outcome.usage());
        } else {
            LlmSpanTags.error(span, outcome.error());
        }
        return outcome;
    }

    private LlmResponse responseOf(StreamOutcome outcome, String modelServed) {
        return new LlmResponse(null, null, modelServed, null, outcome.finishReason(), outcome.usage(), false);
    }

    private long deadlineMs(LlmRequest request) {
        long byTotal = System.currentTimeMillis() + timeouts.totalMs();
        if (request.deadlineAt() != null) {
            return Math.min(byTotal, request.deadlineAt().toEpochMilli());
        }
        return byTotal;
    }

    private LlmException timeoutError(String message) {
        return new LlmException(new LlmError(LlmErrorCategory.TIMEOUT_TOTAL, null, true, false, message));
    }

    private void sleepBackoff(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(new LlmError(LlmErrorCategory.CANCELLED, null, false, false,
                    "重试等待被中断"), e);
        }
    }

    private void validate(LlmRequest request) {
        if (request == null) {
            throw invalid("请求不能为空");
        }
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw invalid("requestId 不能为空");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw invalid("model 不能为空");
        }
        List<ChatMessage> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            throw invalid("messages 不能为空");
        }
        if (request.maxTokens() != null && request.maxTokens() <= 0) {
            throw invalid("maxTokens 必须大于 0");
        }
        if (request.deadlineAt() != null && !request.deadlineAt().isAfter(Instant.now())) {
            throw invalid("deadlineAt 必须晚于当前时间");
        }
    }

    private LlmException invalid(String message) {
        return new LlmException(new LlmError(LlmErrorCategory.INVALID_REQUEST, null, false, false, message));
    }

    private LlmCallRecord successRecord(LlmRequest request, String providerId, LlmResponse response,
                                        LlmUsage usage, long latencyMs, int attempts, boolean stream) {
        return new LlmCallRecord(request.requestId(), request.tenantId(), request.appId(), request.userId(),
                "chat", providerId, null, request.model(), response.modelServed(), stream, attempts, "SUCCESS",
                null, null, null, null,
                usage.inputTokens(), usage.outputTokens(), usage.cachedReadTokens(),
                usage.cachedWriteTokens(), usage.reasoningTokens(), usage.source().name(), latencyMs, null);
    }

    private LlmCallRecord failureRecord(LlmRequest request, String providerId, LlmError error,
                                        long latencyMs, int attempts) {
        return failureRecord(request, providerId, error, latencyMs, attempts, null, "FAILED");
    }

    private LlmCallRecord failureRecord(LlmRequest request, String providerId, LlmError error,
                                        long latencyMs, int attempts, LlmUsage usage, String status) {
        return new LlmCallRecord(request.requestId(), request.tenantId(), request.appId(), request.userId(),
                "chat", providerId, null, request.model(), null, "INTERRUPTED".equals(status) || "CANCELLED".equals(status),
                attempts, status,
                error.category().name(), error.providerCode(), error.retryable(), error.billable(),
                usage == null ? 0 : usage.inputTokens(), usage == null ? 0 : usage.outputTokens(),
                usage == null ? 0 : usage.cachedReadTokens(), usage == null ? 0 : usage.cachedWriteTokens(),
                usage == null ? 0 : usage.reasoningTokens(),
                usage == null ? null : usage.source().name(), latencyMs, null);
    }
}
