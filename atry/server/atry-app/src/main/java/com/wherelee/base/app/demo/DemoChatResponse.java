package com.wherelee.base.app.demo;

import java.math.BigDecimal;

/**
 * 演示响应：回答 + 用量 + 本次成本 + 结算后余额。
 *
 * <p>刻意把「调用（llm）→ 计量计价（metering）→ 余额」三段结果放在同一个响应里，
 * 便于演示与面试讲解；它不是稳定对外 API，字段随演示需要演进。</p>
 */
public record DemoChatResponse(
        String requestId,
        String provider,
        String model,
        String content,
        String finishReason,
        long latencyMs,
        Usage usage,
        Money cost,
        Balance balance
) {

    /** 用量明细：分 token 类型，totalTokens 为各项之和。 */
    public record Usage(long inputTokens, long outputTokens, long cachedReadTokens,
                        long cachedWriteTokens, long reasoningTokens, long totalTokens, String source) {
    }

    /** 本次调用按价格版本估算的成本（与结算同一套价格路径）。 */
    public record Money(BigDecimal amount, String currency) {
    }

    /** 结算后账户余额（balance 为总额，frozen 为进行中的预扣）。 */
    public record Balance(BigDecimal balance, BigDecimal frozen, String currency) {
    }
}
