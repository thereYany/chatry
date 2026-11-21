package com.wherelee.base.modules.contracts.llm;

/** 稳定的 LLM 错误分类（三轴：来源 × 可重试 × 是否计费）。 */
public enum LlmErrorCategory {
    AUTH,
    INVALID_REQUEST,
    RATE_LIMITED,
    QUOTA_EXHAUSTED,
    TIMEOUT_CONNECT,
    TIMEOUT_FIRST_TOKEN,
    TIMEOUT_IDLE,
    TIMEOUT_TOTAL,
    UPSTREAM_5XX,
    UPSTREAM_OVERLOADED,
    CONTENT_FILTERED,
    CONTEXT_TOO_LONG,
    STREAM_INTERRUPTED,
    CANCELLED,
    INTERNAL
}
