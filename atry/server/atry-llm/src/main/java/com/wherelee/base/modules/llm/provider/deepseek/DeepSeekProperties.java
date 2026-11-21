package com.wherelee.base.modules.llm.provider.deepseek;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** DeepSeek（OpenAI 兼容）接入配置；key 只从环境/密钥系统注入，禁止写入代码/日志。 */
@ConfigurationProperties(prefix = "atry.llm.deepseek")
public record DeepSeekProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String defaultModel,
        boolean thinkingDisabled,
        int connectTimeoutMs,
        int totalTimeoutMs
) {
}
