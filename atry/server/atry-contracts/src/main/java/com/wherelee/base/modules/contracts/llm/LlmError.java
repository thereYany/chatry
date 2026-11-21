package com.wherelee.base.modules.contracts.llm;

/** 统一的 LLM 错误描述（providerCode 保留上游原值，便于排障/对账）。 */
public record LlmError(
        LlmErrorCategory category,
        String providerCode,
        boolean retryable,
        boolean billable,
        String message
) {
}
