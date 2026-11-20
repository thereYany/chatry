package com.wherelee.chatry.chat.web;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.platform.common.web.TraceIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import com.wherelee.base.modules.platform.common.ErrorResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ChatExceptionHandlerTest {

    private final ChatExceptionHandler handler = new ChatExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/x");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mapsQuotaExhaustedTo402WithStableCode() {
        MDC.put(TraceIdFilter.MDC_KEY, "trace-9");
        ResponseEntity<ErrorResponse> response = handler.onLlmException(
                new LlmException(new LlmError(LlmErrorCategory.QUOTA_EXHAUSTED, null, false, false, "额度不足")),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody().code()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(response.getBody().message()).isEqualTo("额度不足");
        assertThat(response.getBody().traceId()).isEqualTo("trace-9");
        assertThat(response.getBody().path()).isEqualTo("/api/chat/x");
    }

    @Test
    void mapsRateLimitTo429() {
        ResponseEntity<ErrorResponse> response = handler.onLlmException(
                new LlmException(new LlmError(LlmErrorCategory.RATE_LIMITED, null, false, false, "太频繁")),
                request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().code()).isEqualTo("RATE_LIMITED");
    }
}
