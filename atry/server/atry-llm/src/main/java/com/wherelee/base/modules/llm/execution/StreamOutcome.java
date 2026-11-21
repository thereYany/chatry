package com.wherelee.base.modules.llm.execution;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmUsage;

/** 流式调用的最终结果（含已完成/失败/取消与部分用量）。 */
public record StreamOutcome(
        boolean completed,
        LlmUsage usage,
        String finishReason,
        LlmError error,
        int chunks,
        long latencyMs,
        Long ttftMs
) {

    public static StreamOutcome completed(LlmUsage usage, String finishReason, int chunks, long latencyMs) {
        return new StreamOutcome(true, usage, finishReason, null, chunks, latencyMs, null);
    }

    public static StreamOutcome completed(LlmUsage usage, String finishReason, int chunks, long latencyMs,
                                          Long ttftMs) {
        return new StreamOutcome(true, usage, finishReason, null, chunks, latencyMs, ttftMs);
    }

    public static StreamOutcome failed(LlmError error, LlmUsage partialUsage, int chunks, long latencyMs) {
        return new StreamOutcome(false, partialUsage, null, error, chunks, latencyMs, null);
    }
}
