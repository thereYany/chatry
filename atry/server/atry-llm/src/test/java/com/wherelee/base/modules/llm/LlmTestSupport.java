package com.wherelee.base.modules.llm;

import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.llm.config.LlmProperties;

/**
 * LLM 测试配置夹具：集中构造 LlmProperties，避免各测试重复一长串构造参数。
 *
 * <p>默认值刻意取小（重试 2 次、退避 1ms、超时几百毫秒），保证单测快速且确定性。</p>
 */
public final class LlmTestSupport {

    private LlmTestSupport() {
    }

    public static LlmProperties.Mock mock() {
        return new LlmProperties.Mock(0, "mock-fail", "mock-flaky", "mock-slow", 400,
                128, 4, 0, "mock-stream-mid-fail");
    }

    public static LlmProperties.Timeouts timeouts() {
        return new LlmProperties.Timeouts(1000, 300, 300, 2000);
    }

    public static LlmProperties.Retry retry() {
        return new LlmProperties.Retry(2, 1, 5, 0);
    }

    public static LlmProperties.Circuit circuit() {
        return new LlmProperties.Circuit(50, 4, 80, 2, 0, 60);
    }

    public static LlmProperties.RateLimit rateLimit() {
        return new LlmProperties.RateLimit(30_000, 300_000, 1000, "local");
    }

    /** 默认配置：mock 模型可用、重试 2 次、熔断最小 4 次、限流很大。 */
    public static LlmProperties properties() {
        return new LlmProperties(mock(), timeouts(), retry(), circuit(), rateLimit());
    }

    public static LlmProperties withMock(LlmProperties.Mock mock) {
        return new LlmProperties(mock, timeouts(), retry(), circuit(), rateLimit());
    }

    public static LlmProperties withTimeouts(LlmProperties.Timeouts timeouts) {
        return new LlmProperties(mock(), timeouts, retry(), circuit(), rateLimit());
    }

    public static LlmProperties withRetry(LlmProperties.Retry retry) {
        return new LlmProperties(mock(), timeouts(), retry, circuit(), rateLimit());
    }

    public static LlmProperties withCircuit(LlmProperties.Circuit circuit) {
        return new LlmProperties(mock(), timeouts(), retry(), circuit, rateLimit());
    }

    public static LlmProperties withRateLimit(LlmProperties.RateLimit rateLimit) {
        return new LlmProperties(mock(), timeouts(), retry(), circuit(), rateLimit);
    }

    public static LlmUsage usage(long input, long output, long cachedRead) {
        return new LlmUsage(input, output, cachedRead, 0, 0, LlmUsage.Source.PROVIDER);
    }
}
