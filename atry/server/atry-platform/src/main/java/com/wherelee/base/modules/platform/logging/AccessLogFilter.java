package com.wherelee.base.modules.platform.logging;

import com.wherelee.base.modules.platform.common.web.ClientIps;
import com.wherelee.base.modules.platform.config.SecurityProperties;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 访问日志：每条 HTTP 请求一行 key=value，便于 grep 与日志平台解析。
 *
 * <p>字段固定：method/path/status/latencyMs/userId/tenantId/ip/traceId/ua。
 * 只记 path 不记 query，避免 token 出现在 URL 里被落盘；静态资源与健康检查跳过。
 * 慢请求额外标记 {@code slow=true}，方便告警规则直接匹配。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.logging", name = "access-log-enabled",
        havingValue = "true", matchIfMissing = true)
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class AccessLogFilter extends OncePerRequestFilter {

    public static final String ACCESS_LOGGER = "ACCESS_LOG";

    private static final Logger ACCESS = LoggerFactory.getLogger(ACCESS_LOGGER);
    private static final String[] STATIC_SUFFIXES = {
            ".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".map",
            ".woff", ".woff2", ".ttf", ".eot", ".webp"
    };

    private final LoggingProperties loggingProperties;
    private final SecurityProperties securityProperties;

    public AccessLogFilter(LoggingProperties loggingProperties, SecurityProperties securityProperties) {
        this.loggingProperties = loggingProperties;
        this.securityProperties = securityProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (!shouldSkip(request)) {
                if (request.isAsyncStarted()) {
                    // SSE 等异步请求：chain 返回时流还没结束，等异步完成再记，延迟才真实
                    logOnAsyncCompletion(request, response, startedAt);
                } else {
                    writeAccessLog(request, response, elapsedMs(startedAt),
                            request.getAttribute("userId"), request.getAttribute("tenantId"), MDC.get("traceId"));
                }
            }
        }
    }

    /** SSE/异步请求的访问日志：在 onComplete/onTimeout/onError 时记录一次。 */
    private void logOnAsyncCompletion(HttpServletRequest request, HttpServletResponse response, long startedAt) {
        Object userId = request.getAttribute("userId");
        Object tenantId = request.getAttribute("tenantId");
        String traceId = MDC.get("traceId");
        AtomicBoolean logged = new AtomicBoolean(false);
        try {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    logOnce(event);
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    logOnce(event);
                }

                @Override
                public void onError(AsyncEvent event) {
                    logOnce(event);
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    // 同一请求再次异步：沿用本次记录即可
                }

                private void logOnce(AsyncEvent event) {
                    if (!logged.compareAndSet(false, true)) {
                        return;
                    }
                    int status = response.getStatus();
                    writeAccessLog(request, status, elapsedMs(startedAt), userId, tenantId, traceId);
                }
            });
        } catch (IllegalStateException e) {
            // 异步已结束（极短流）：退化为立即记录
            writeAccessLog(request, response, elapsedMs(startedAt), userId, tenantId, traceId);
        }
    }

    private boolean shouldSkip(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || path.isEmpty()) {
            return true;
        }
        if (path.startsWith("/actuator") || "/api/health".equals(path)) {
            return true;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String suffix : STATIC_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private void writeAccessLog(HttpServletRequest request, HttpServletResponse response, long elapsedMs,
                                Object userId, Object tenantId, String traceId) {
        writeAccessLog(request, response.getStatus(), elapsedMs, userId, tenantId, traceId);
    }

    private void writeAccessLog(HttpServletRequest request, int status, long elapsedMs,
                                Object userId, Object tenantId, String traceId) {
        String userAgent = request.getHeader("User-Agent");
        boolean slow = elapsedMs >= loggingProperties.accessLogSlowMs();
        ACCESS.info("access method={} path={} status={} latencyMs={} userId={} tenantId={} ip={} "
                        + "slow={} traceId={} ua=\"{}\"",
                request.getMethod(),
                request.getRequestURI(),
                status,
                elapsedMs,
                userId == null ? "-" : userId,
                tenantId == null ? "-" : tenantId,
                clientIp(request),
                slow,
                traceId == null ? "-" : traceId,
                userAgent == null ? "-" : abbreviate(SensitiveDataMasker.mask(userAgent), 160));
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private String clientIp(HttpServletRequest request) {
        return ClientIps.resolve(request, securityProperties != null && securityProperties.trustForwardedHeaders());
    }

    private String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
