package com.wherelee.base.modules.contracts.llm;

/** 统一 LLM 响应；内容默认不持久化，由调用方决定。 */
public record LlmResponse(
        String requestId,
        String provider,
        String modelServed,
        String content,
        String finishReason,
        LlmUsage usage,
        boolean degraded
) {
}
