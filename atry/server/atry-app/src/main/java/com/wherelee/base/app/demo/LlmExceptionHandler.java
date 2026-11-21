package com.wherelee.base.app.demo;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * LLM 错误分类 → HTTP 状态码（演示宿主层）。
 *
 * <p>内核只保证「错误分类稳定」（category/retryable/billable），
 * 具体 HTTP 映射属于接入方决策：演示宿主把它映射成对调用方友好的状态码，
 * 且必须比 platform 的兜底 {@code @RestControllerAdvice} 更早生效。</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LlmExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LlmExceptionHandler.class);

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<LlmErrorResponse> handleLlm(LlmException e) {
        LlmError error = e.error();
        HttpStatus status = statusOf(error);
        log.warn("llm.error category={} providerCode={} retryable={} billable={} status={} message={}",
                error.category(), error.providerCode(), error.retryable(), error.billable(), status.value(),
                error.message());
        return ResponseEntity.status(status).body(new LlmErrorResponse(
                error.message(), error.category().name(), error.providerCode(),
                error.retryable(), error.billable()));
    }

    static HttpStatus statusOf(LlmError error) {
        LlmErrorCategory category = error.category() == null ? LlmErrorCategory.INTERNAL : error.category();
        return switch (category) {
            case AUTH, UPSTREAM_5XX -> HttpStatus.BAD_GATEWAY;
            case INVALID_REQUEST, CONTEXT_TOO_LONG -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case QUOTA_EXHAUSTED -> HttpStatus.PAYMENT_REQUIRED;
            case TIMEOUT_CONNECT, TIMEOUT_FIRST_TOKEN, TIMEOUT_IDLE, TIMEOUT_TOTAL -> HttpStatus.GATEWAY_TIMEOUT;
            case UPSTREAM_OVERLOADED -> HttpStatus.SERVICE_UNAVAILABLE;
            case CONTENT_FILTERED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case STREAM_INTERRUPTED, CANCELLED -> HttpStatus.BAD_GATEWAY;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** 错误响应：保留稳定分类字段，调用方可按 category/retryable/billable 决策。 */
    public record LlmErrorResponse(String message, String category, String providerCode,
                                   boolean retryable, boolean billable) {
    }
}
