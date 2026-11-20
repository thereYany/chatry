package com.wherelee.chatry.chat;

import com.wherelee.base.modules.contracts.llm.LlmUsage;

/** 一次回复的用量视图（对外 JSON 用）。 */
public record ChatUsageView(int inputTokens, int outputTokens, int cachedReadTokens,
                            int reasoningTokens, int totalTokens, String source) {

    public static ChatUsageView from(LlmUsage usage) {
        if (usage == null) {
            return new ChatUsageView(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED.name());
        }
        return new ChatUsageView(
                (int) Math.min(Integer.MAX_VALUE, usage.inputTokens()),
                (int) Math.min(Integer.MAX_VALUE, usage.outputTokens()),
                (int) Math.min(Integer.MAX_VALUE, usage.cachedReadTokens()),
                (int) Math.min(Integer.MAX_VALUE, usage.reasoningTokens()),
                (int) Math.min(Integer.MAX_VALUE, usage.totalTokens()),
                usage.source() == null ? LlmUsage.Source.ESTIMATED.name() : usage.source().name());
    }
}
