package com.wherelee.base.modules.llm.observability;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化 LLM 事件日志：一条 JSON 还原一次调用/尝试/结果。
 *
 * <p>固定 envelope + 分组（trace/subject/llm/usage/latency/outcome/cost/sampling/content）。
 * 默认不记录 prompt/response；只记录元数据与 token/成本，便于 agent 按 requestId/traceId 检索。</p>
 */
@Component
public class LlmEventLogger {

    public static final String LOGGER_NAME = "LLM_EVENTS";
    private static final Logger events = LoggerFactory.getLogger(LOGGER_NAME);

    private final ObjectMapper objectMapper;

    public LlmEventLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void started(LlmRequest request, String providerId) {
        emit("llm.call.started", request, envelope(request, providerId, null, null, null, null, "STARTED", null));
    }

    public void attemptFailed(LlmRequest request, String providerId, LlmError error, int attempt,
                              boolean willRetry, long backoffMs) {
        Map<String, Object> event = envelope(request, providerId, null, null, error, null, "FAILED", null);
        event.put("attempt", Map.of("number", attempt, "will_retry", willRetry, "backoff_ms", backoffMs));
        emit("llm.attempt.failed", request, event);
    }

    public void finished(LlmRequest request, String providerId, LlmResponse response, LlmUsage usage,
                         long latencyMs, int attempts, boolean stream, BigDecimal cost) {
        Map<String, Object> event = envelope(request, providerId, response, usage, null, latencyMs, "SUCCESS", cost);
        event.put("attempt_count", attempts);
        event.put("stream", stream);
        emit("llm.call.finished", request, event);
    }

    public void failed(LlmRequest request, String providerId, LlmError error, long latencyMs,
                       int attempts, boolean stream, BigDecimal cost) {
        Map<String, Object> event = envelope(request, providerId, null, null, error, latencyMs, "FAILED", cost);
        event.put("attempt_count", attempts);
        event.put("stream", stream);
        emit("llm.call.failed", request, event);
    }

    public void annotation(String name, String status, long latencyMs) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_name", "llm.annotation.call");
        event.put("event_version", 1);
        event.put("timestamp", Instant.now().toString());
        event.put("trace_id", MDC.get("traceId"));
        event.put("annotation", Map.of("name", name == null ? "" : name, "status", status));
        event.put("latency", Map.of("total_ms", latencyMs));
        event.put("outcome", Map.of("status", status));
        events.info(toJson(event));
    }

    private Map<String, Object> envelope(LlmRequest request, String providerId, LlmResponse response,
                                         LlmUsage usage, LlmError error, Long latencyMs, String status,
                                         BigDecimal cost) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_name", "llm.call");
        event.put("event_version", 1);
        event.put("timestamp", Instant.now().toString());
        event.put("trace_id", MDC.get("traceId"));

        event.put("subject", Map.of(
                "tenant_id", request.tenantId(),
                "app_id", request.appId(),
                "user_id", request.userId()));

        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("provider", providerId);
        llm.put("model_requested", request.model());
        if (response != null && response.modelServed() != null) {
            llm.put("model_served", response.modelServed());
        }
        llm.put("operation", "chat");
        llm.put("stream", request.stream());
        event.put("llm", llm);

        if (usage != null) {
            event.put("usage", Map.of(
                    "input_tokens", usage.inputTokens(),
                    "output_tokens", usage.outputTokens(),
                    "cached_read_tokens", usage.cachedReadTokens(),
                    "cached_write_tokens", usage.cachedWriteTokens(),
                    "reasoning_tokens", usage.reasoningTokens(),
                    "total_tokens", usage.totalTokens(),
                    "source", usage.source().name()));
        }
        if (latencyMs != null) {
            event.put("latency", Map.of("total_ms", latencyMs));
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", status);
        if (error != null) {
            outcome.put("error_category", error.category().name());
            outcome.put("provider_code", truncate(error.providerCode()));
            outcome.put("retryable", error.retryable());
            outcome.put("billable", error.billable());
        }
        event.put("outcome", outcome);
        event.put("cost", costGroup(cost));
        event.put("sampling", Map.of("sampled", true));
        event.put("content", Map.of("content_captured", false));
        return event;
    }

    private void emit(String eventName, LlmRequest request, Map<String, Object> event) {
        event.put("event_name", eventName);
        event.put("request_id", request.requestId());
        try {
            events.info(toJson(event));
        } catch (RuntimeException e) {
            events.warn("llm.event.serialize_failed eventName={} requestId={}", eventName, request.requestId());
        }
    }

    private String toJson(Map<String, Object> event) {
        return objectMapper.writeValueAsString(event);
    }

    /** 成本分组：金额 6 位小数（与计量金额精度一致），并给出 micros 便于聚合。 */
    private Map<String, Object> costGroup(BigDecimal cost) {
        BigDecimal amount = (cost == null ? BigDecimal.ZERO : cost).setScale(6, RoundingMode.CEILING);
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("currency", "CNY");
        group.put("amount", amount);
        group.put("amount_micros", amount.movePointRight(6).longValueExact());
        return group;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
