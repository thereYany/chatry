package com.wherelee.base.modules.platform.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务 span 封装测试。
 *
 * <p>不变式：trace 执行必须透传返回值/异常；span 结束后 MDC 恢复原值；空标签过滤不产生噪声。</p>
 */
class TraceSpansTest {

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void noop实现透传返回值与异常() {
        TraceSpans spans = TraceSpans.noop();
        assertThat(spans.trace("x", Map.of("k", "v"), () -> "result")).isEqualTo("result");
        assertThatThrownBy(() -> spans.trace("x", Map.of(), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 标签工具过滤null键值对() {
        Map<String, String> tags = TraceSpans.tags("a", "1", null, "x", "b", null);
        assertThat(tags).containsEntry("a", "1").hasSize(1);
        assertThat(TraceSpans.tags()).isEmpty();
        // 奇数个参数时最后一个忽略
        assertThat(TraceSpans.tags("onlyKey")).isEmpty();
    }

    @Test
    void 输出span时MDC写入traceId且结束后恢复外层值() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("tid-123");
        when(tracer.withSpan(span)).thenReturn(scope);

        TraceSpans spans = new TraceSpans(tracer);
        MDC.put("traceId", "outer");

        String result = spans.trace("payment.confirm", Map.of("payment.bizNo", "B1"),
                () -> {
                    assertThat(MDC.get("traceId")).isEqualTo("tid-123");
                    return "ok";
                });

        assertThat(result).isEqualTo("ok");
        assertThat(MDC.get("traceId")).isEqualTo("outer");
        verify(span).tag("payment.bizNo", "B1");
        verify(span).end();
    }

    @Test
    void 异常时标记span错误并结束() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        when(span.context()).thenReturn(null);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));

        TraceSpans spans = new TraceSpans(tracer);
        IllegalStateException failure = new IllegalStateException("boom");
        assertThatThrownBy(() -> spans.trace("x", Map.of(), () -> {
            throw failure;
        })).isSameAs(failure);

        verify(span).error(failure);
        verify(span).end();
        // 空标签不得写入 span
        verify(span, never()).tag(anyString(), anyString());
    }
}
