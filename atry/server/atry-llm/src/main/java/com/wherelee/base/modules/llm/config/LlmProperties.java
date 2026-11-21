package com.wherelee.base.modules.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** LLM 框架配置（P2：超时/重试/熔断/限流；P5 扩展真实 provider）。 */
@ConfigurationProperties(prefix = "atry.llm")
public record LlmProperties(
        Mock mock,
        Timeouts timeouts,
        Retry retry,
        Circuit circuit,
        RateLimit rateLimit
) {

    /** mock provider 行为（仅测试/本地）。 */
    public record Mock(long delayMs, String failureModel, String flakyModel, String slowModel,
                       long slowDelayMs, int defaultMaxTokens,
                       int chunkCount, long chunkDelayMs, String midStreamFailModel) {
    }

    /** 四层超时（P2 非流式用 total；流式 P2c/P3 用 firstToken/chunkIdle）。 */
    public record Timeouts(long connectMs, long firstTokenMs, long chunkIdleMs, long totalMs) {
    }

    public record Retry(int maxAttempts, long initialBackoffMs, long maxBackoffMs, double jitterRatio) {
    }

    public record Circuit(int failureRateThreshold, int minimumCalls, long openMs, int halfOpenProbes,
                          long slowCallMs, int slowCallRateThreshold) {
    }

    /**
     * 限流配置。
     *
     * <p>{@code backend}：{@code auto}（默认，有 Redis 时多实例共享）/{@code redis}/{@code local}。
     * Redis 不可用时自动降级为每实例本地令牌桶，并记 {@code infra.redis.fallback} 指标。</p>
     */
    public record RateLimit(int tenantRpm, int providerRpm, int maxInFlight, String backend) {
    }
}
