package com.wherelee.base.modules.llm.observability;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.llm.annotation.LlmCall;
import com.wherelee.base.modules.llm.annotation.LlmStream;
import com.wherelee.base.modules.llm.aop.LlmCallAspect;
import com.wherelee.base.modules.llm.aop.LlmStreamAspect;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 可观测性测试：指标低基数、事件日志脱敏、span 语义标签。
 *
 * <p>业务不变式：指标标签不能带 tenant/user/request/prompt 等高基数字段（成本指标例外）；
 * 事件日志绝不记录 prompt/response 内容；成本金额 6 位小数并给出 micros。</p>
 */
class LlmObservabilityTest {

    private static LlmRequest request() {
        return new LlmRequest("R1", 1, 0, 2, "mock-small",
                List.of(new ChatMessage("user", "绝密提示词-不要落日志")), 16, false,
                Instant.now().plusSeconds(5), null);
    }

    private static LlmResponse response() {
        return new LlmResponse("R1", "mock", "mock-small", "模型回复内容", "stop",
                new LlmUsage(10, 5, 2, 0, 0, LlmUsage.Source.PROVIDER), false);
    }

    // ---------- 指标 ----------

    @Test
    void 成功指标包含调用数_耗时_token与成本() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmMetrics metrics = new LlmMetrics(registry);

        metrics.success("mock", "mock-small", false, 123,
                new LlmUsage(10, 5, 2, 0, 1, LlmUsage.Source.PROVIDER), null);
        metrics.cost(7L, "mock-small", new BigDecimal("0.000015"));

        assertThat(registry.get("atry.llm.calls")
                .tag("provider", "mock").tag("model", "mock-small")
                .tag("stream", "false").tag("status", "SUCCESS")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("atry.llm.duration").tag("provider", "mock").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("atry.llm.tokens")
                .tag("token_type", "input").counter().count()).isEqualTo(10);
        assertThat(registry.get("atry.llm.tokens")
                .tag("token_type", "output").counter().count()).isEqualTo(5);
        assertThat(registry.get("atry.llm.tokens")
                .tag("token_type", "cached_read").counter().count()).isEqualTo(2);
        assertThat(registry.get("atry.llm.tokens")
                .tag("token_type", "reasoning").counter().count()).isEqualTo(1);
        // 成本指标允许带 tenant（成本归因需要）
        assertThat(registry.get("atry.llm.cost")
                .tag("tenant", "7").tag("model", "mock-small")
                .counter().count()).isEqualTo(0.000015);
    }

    @Test
    void 流式成功额外记录TTFT_失败记录错误分类() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmMetrics metrics = new LlmMetrics(registry);

        metrics.success("mock", "m", true, 100, new LlmUsage(1, 1, 0, 0, 0,
                LlmUsage.Source.PROVIDER), 25L);
        assertThat(registry.get("atry.llm.ttft").timer().count()).isEqualTo(1);

        metrics.failure("mock", "m", false,
                new LlmError(LlmErrorCategory.UPSTREAM_5XX, "E", true, false, "失败"), 50);
        assertThat(registry.get("atry.llm.errors")
                .tag("category", "UPSTREAM_5XX").tag("retryable", "true")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("atry.llm.calls").tag("status", "FAILED").counter().count())
                .isEqualTo(1);
    }

    @Test
    void 零或负成本不记录_重试与预算指标() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmMetrics metrics = new LlmMetrics(registry);
        metrics.cost(1L, "m", BigDecimal.ZERO);
        metrics.cost(1L, "m", new BigDecimal("-1"));

        assertThat(registry.find("atry.llm.cost").counters()).isEmpty();

        metrics.retry("mock", true);
        metrics.retry("mock", false);
        assertThat(registry.get("atry.llm.retries").tag("outcome", "attempted")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("atry.llm.retries").tag("outcome", "budget_exhausted")
                .counter().count()).isEqualTo(1);

        metrics.rejected("tenant");
        metrics.budgetExceeded("b", "REJECT");
        assertThat(registry.get("atry.llm.ratelimit.rejected").tag("scope", "tenant")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("atry.llm.budget.exceeded").tag("action", "REJECT")
                .counter().count()).isEqualTo(1);
    }

    // ---------- 事件日志 ----------

    private static ObjectMapper capturingMapper(java.util.concurrent.atomic.AtomicReference<Object> holder) {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    holder.set(invocation.getArgument(0));
                    return "{}";
                });
        return mapper;
    }

    @Test
    void 完成事件包含用量成本且不含内容() {
        java.util.concurrent.atomic.AtomicReference<Object> captor =
                new java.util.concurrent.atomic.AtomicReference<>();
        LlmEventLogger logger = new LlmEventLogger(capturingMapper(captor));

        logger.finished(request(), "mock", response(),
                new LlmUsage(10, 5, 0, 0, 0, LlmUsage.Source.PROVIDER), 88, 1, false,
                new BigDecimal("0.000015"));

        Map<String, Object> event = (Map<String, Object>) captor.get();
        assertThat(event).containsEntry("event_name", "llm.call.finished")
                .containsEntry("request_id", "R1");
        Map<String, Object> usage = (Map<String, Object>) event.get("usage");
        assertThat(usage).containsEntry("input_tokens", 10L).containsEntry("output_tokens", 5L)
                .containsEntry("total_tokens", 15L).containsEntry("source", "PROVIDER");
        Map<String, Object> cost = (Map<String, Object>) event.get("cost");
        assertThat(cost).containsEntry("currency", "CNY")
                .containsEntry("amount_micros", 15L);
        Map<String, Object> content = (Map<String, Object>) event.get("content");
        assertThat(content).containsEntry("content_captured", false);

        // 事件不得包含 prompt/response 原文
        String serialized = String.valueOf(event);
        assertThat(serialized).doesNotContain("绝密提示词").doesNotContain("模型回复内容");
    }

    @Test
    void 失败事件记录错误分类并可截断超长providerCode() {
        java.util.concurrent.atomic.AtomicReference<Object> captor =
                new java.util.concurrent.atomic.AtomicReference<>();
        LlmEventLogger logger = new LlmEventLogger(capturingMapper(captor));

        logger.failed(request(), "mock", new LlmError(LlmErrorCategory.UPSTREAM_5XX,
                        "x".repeat(100), true, false, "上游失败"), 20, 2, false,
                BigDecimal.ZERO);

        Map<String, Object> event = (Map<String, Object>) captor.get();
        Map<String, Object> outcome = (Map<String, Object>) event.get("outcome");
        assertThat(outcome).containsEntry("status", "FAILED")
                .containsEntry("error_category", "UPSTREAM_5XX")
                .containsEntry("retryable", true);
        assertThat((String) outcome.get("provider_code")).hasSize(64);
    }

    @Test
    void 尝试失败事件带重试决策() {
        java.util.concurrent.atomic.AtomicReference<Object> captor =
                new java.util.concurrent.atomic.AtomicReference<>();
        LlmEventLogger logger = new LlmEventLogger(capturingMapper(captor));

        logger.attemptFailed(request(), "mock",
                new LlmError(LlmErrorCategory.TIMEOUT_TOTAL, "T", true, false, "超时"),
                2, true, 300);

        Map<String, Object> event = (Map<String, Object>) captor.get();
        assertThat(event).containsEntry("event_name", "llm.attempt.failed");
        Map<String, Object> attempt = (Map<String, Object>) event.get("attempt");
        assertThat(attempt).containsEntry("number", 2)
                .containsEntry("will_retry", true)
                .containsEntry("backoff_ms", 300L);
    }

    // ---------- span 标签 ----------

    @Test
    void span标签遵循GenAI语义且空值安全() {
        Span span = mock(Span.class);
        LlmSpanTags.request(span, request(), "mock");
        verify(span).tag("gen_ai.operation.name", "chat");
        verify(span).tag("gen_ai.provider.name", "mock");
        verify(span).tag("gen_ai.request.model", "mock-small");
        verify(span).tag("gen_ai.request.max_tokens", "16");

        LlmSpanTags.response(span, response(),
                new LlmUsage(10, 5, 0, 0, 0, LlmUsage.Source.PROVIDER));
        verify(span).tag("gen_ai.response.finish_reasons", "stop");
        verify(span).tag("gen_ai.usage.input_tokens", "10");

        LlmSpanTags.error(span, new LlmError(LlmErrorCategory.AUTH, null, false, false, "x"));
        verify(span).tag("error.type", "auth");

        // null 输入不抛错
        LlmSpanTags.request(span, request(), null);
        LlmSpanTags.error(span, null);
        LlmSpanTags.error(null, new LlmError(LlmErrorCategory.AUTH, null, false, false, "x"));
        verify(span, never()).tag(anyString(), org.mockito.ArgumentMatchers.isNull());
    }

    // ---------- 注解切面 ----------

    @Test
    void 注解切面记录成功与失败调用() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmEventLogger events = mock(LlmEventLogger.class);

        AspectJProxyFactory callFactory = new AspectJProxyFactory(new AnnotatedTarget());
        callFactory.addAspect(new LlmCallAspect(registry, events));
        AnnotatedTarget callProxy = callFactory.getProxy();

        AspectJProxyFactory streamFactory = new AspectJProxyFactory(new AnnotatedTarget());
        streamFactory.addAspect(new LlmStreamAspect(registry, events));
        AnnotatedTarget streamProxy = streamFactory.getProxy();

        assertThat(callProxy.call()).isEqualTo("ok");
        assertThatThrownBy(callProxy::callFailure).isInstanceOf(IllegalStateException.class);
        streamProxy.stream();
        assertThatThrownBy(streamProxy::streamFailure).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("atry.llm.annotated.calls")
                .tag("mode", "sync").tag("status", "SUCCESS").counter().count()).isEqualTo(1);
        assertThat(registry.get("atry.llm.annotated.calls")
                .tag("mode", "sync").tag("status", "FAILED").counter().count()).isEqualTo(1);
        assertThat(registry.get("atry.llm.annotated.calls")
                .tag("mode", "stream").tag("status", "SUCCESS").counter().count()).isEqualTo(1);
        assertThat(registry.get("atry.llm.annotated.calls")
                .tag("mode", "stream").tag("status", "FAILED").counter().count()).isEqualTo(1);
    }

    public static class AnnotatedTarget {
        @LlmCall("unit-call")
        public String call() {
            return "ok";
        }

        @LlmCall("unit-call")
        public void callFailure() {
            throw new IllegalStateException("boom");
        }

        @LlmStream("unit-stream")
        public void stream() {
        }

        @LlmStream("unit-stream")
        public void streamFailure() {
            throw new IllegalStateException("boom");
        }
    }
}
