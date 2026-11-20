package com.wherelee.chatry.chat.web;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 把 LLM 内核错误分类映射成稳定的 HTTP 语义（额度不足 402、限流 429）。 */
@RestControllerAdvice
public class ChatExceptionHandler {

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<Map<String, String>> onLlmException(LlmException exception) {
        LlmError error = exception.error();
        HttpStatus status = switch (error.category()) {
            case QUOTA_EXHAUSTED -> HttpStatus.PAYMENT_REQUIRED;
            case RATE_LIMITED, UPSTREAM_OVERLOADED -> HttpStatus.TOO_MANY_REQUESTS;
            case INVALID_REQUEST, CONTENT_FILTERED, CONTEXT_TOO_LONG -> HttpStatus.BAD_REQUEST;
            case CANCELLED -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(Map.of(
                "category", error.category().name(),
                "message", error.message()));
    }

    static LlmErrorCategory categoryOf(LlmException exception) {
        return exception.error().category();
    }
}
