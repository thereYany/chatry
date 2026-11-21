package com.wherelee.base.modules.contracts.llm;

/** LLM 调用的统一异常：携带稳定错误分类，业务可按 category/retryable/billable 决策。 */
public class LlmException extends RuntimeException {

    private final LlmError error;

    public LlmException(LlmError error) {
        super(error.message());
        this.error = error;
    }

    public LlmException(LlmError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public LlmError error() {
        return error;
    }
}
