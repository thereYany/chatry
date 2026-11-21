package com.wherelee.chatry.chat.web;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.platform.common.ErrorResponse;
import com.wherelee.base.modules.platform.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * LLM 内核错误 → 统一错误体（错误码 = LLM 错误分类，便于前端/网关按码分支）。
 *
 * <p>与内核 {@code ErrorResponse} 保持同一结构：code/message/traceId/path/timestamp/details。</p>
 */
@RestControllerAdvice
public class ChatExceptionHandler {

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<ErrorResponse> onLlmException(LlmException exception, HttpServletRequest request) {
        LlmError error = exception.error();
        HttpStatus status = switch (error.category()) {
            case QUOTA_EXHAUSTED -> HttpStatus.PAYMENT_REQUIRED;
            case RATE_LIMITED, UPSTREAM_OVERLOADED -> HttpStatus.TOO_MANY_REQUESTS;
            case INVALID_REQUEST, CONTENT_FILTERED, CONTEXT_TOO_LONG -> HttpStatus.BAD_REQUEST;
            case CANCELLED -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        ErrorResponse body = new ErrorResponse(
                error.category().name(),
                error.message(),
                TraceIdFilter.currentTraceId(),
                request.getRequestURI(),
                Instant.now(),
                List.of());
        return ResponseEntity.status(status).body(body);
    }
}
