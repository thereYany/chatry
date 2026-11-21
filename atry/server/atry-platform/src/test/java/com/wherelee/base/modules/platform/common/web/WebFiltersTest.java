package com.wherelee.base.modules.platform.common.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TraceId 过滤器与安全响应头过滤器测试。
 *
 * <p>不变式：每个响应必须有可关联的 traceId（响应头 + MDC 一致，请求结束清理 MDC 防线程池串号）；
 * 安全头始终存在，HSTS 只在 HTTPS 请求上添加。</p>
 */
class WebFiltersTest {

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void traceId生成16位十六进制并写入响应头与MDC() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInside = new AtomicReference<>();

        FilterChain chain = (req, res) -> mdcInside.set(MDC.get(TraceIdFilter.MDC_KEY));
        filter.doFilter(request, response, chain);

        String traceId = response.getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).matches("[0-9a-f]{16}");
        assertThat(mdcInside.get()).isEqualTo(traceId);
        // 请求结束后 MDC 必须清理，避免线程复用导致 traceId 串号
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
        assertThat(TraceIdFilter.currentTraceId()).isNull();
    }

    @Test
    void traceId优先复用当前tracer的traceId() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        io.micrometer.tracing.TraceContext context = mock(io.micrometer.tracing.TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("0123456789abcdef");
        @SuppressWarnings("unchecked")
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);

        TraceIdFilter filter = new TraceIdFilter(provider);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                assertThat(TraceIdFilter.currentTraceId()).isEqualTo("0123456789abcdef"));

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("0123456789abcdef");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
        Mockito.verify(span).tag("atry.trace_id", "0123456789abcdef");
    }

    @Test
    void 安全响应头始终存在且HSTS只在HTTPS下添加() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();

        MockHttpServletResponse http = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/health"), http, (req, res) -> {
        });
        assertThat(http.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(http.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(http.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(http.getHeader("Content-Security-Policy"))
                .contains("default-src 'self'", "frame-ancestors 'none'");
        assertThat(http.getHeader("Strict-Transport-Security")).isNull();

        MockHttpServletRequest secureRequest = new MockHttpServletRequest("GET", "/api/health");
        secureRequest.setSecure(true);
        MockHttpServletResponse https = new MockHttpServletResponse();
        filter.doFilter(secureRequest, https, (req, res) -> {
        });
        assertThat(https.getHeader("Strict-Transport-Security")).contains("max-age=31536000");
    }
}
