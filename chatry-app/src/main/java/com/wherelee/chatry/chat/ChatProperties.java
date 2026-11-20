package com.wherelee.chatry.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话域配置。
 *
 * @param defaultModel          未显式指定模型时使用（默认 mock-small，本地无需 key）
 * @param defaultMaxTokens      单次回复上限
 * @param contextMessages       拼进上下文的历史消息条数上限（越近越优先）
 * @param contextChars          拼进上下文的字符预算，超出后丢最旧消息
 * @param deadlineSeconds       非流式调用的墙钟 deadline
 * @param streamDeadlineSeconds 流式调用的墙钟 deadline
 * @param streamTimeoutMs       SSE 连接超时（客户端断开由此回收）
 * @param titleChars            首条消息自动生成标题的截断长度
 */
@ConfigurationProperties(prefix = "chatry.chat")
public record ChatProperties(
        String defaultModel,
        Integer defaultMaxTokens,
        Integer contextMessages,
        Integer contextChars,
        Integer deadlineSeconds,
        Integer streamDeadlineSeconds,
        Long streamTimeoutMs,
        Integer titleChars
) {

    public ChatProperties {
        defaultModel = blank(defaultModel) ? "mock-small" : defaultModel.trim();
        defaultMaxTokens = positive(defaultMaxTokens, 512);
        contextMessages = positive(contextMessages, 20);
        contextChars = positive(contextChars, 12_000);
        deadlineSeconds = positive(deadlineSeconds, 30);
        streamDeadlineSeconds = positive(streamDeadlineSeconds, 300);
        streamTimeoutMs = streamTimeoutMs == null || streamTimeoutMs <= 0 ? 300_000L : streamTimeoutMs;
        titleChars = positive(titleChars, 20);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
