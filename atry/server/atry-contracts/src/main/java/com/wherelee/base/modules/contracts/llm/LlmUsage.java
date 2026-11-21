package com.wherelee.base.modules.contracts.llm;

/** token 用量（分项）；source=ESTIMATED 表示上游未返回、由框架估算。 */
public record LlmUsage(
        long inputTokens,
        long outputTokens,
        long cachedReadTokens,
        long cachedWriteTokens,
        long reasoningTokens,
        Source source
) {
    public enum Source { PROVIDER, ESTIMATED }

    public long totalTokens() {
        return inputTokens + outputTokens + cachedReadTokens + cachedWriteTokens + reasoningTokens;
    }
}
