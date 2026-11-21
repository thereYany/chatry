package com.wherelee.base.modules.llm.resilience;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.llm.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** 单点重试策略：只重试分类为幂等/可恢复的错误；退避指数 + 抖动 + 上限。 */
@Component
public class LlmRetryPolicy {

    private static final Set<LlmErrorCategory> RETRYABLE = Set.of(
            LlmErrorCategory.TIMEOUT_CONNECT,
            LlmErrorCategory.TIMEOUT_FIRST_TOKEN,
            LlmErrorCategory.TIMEOUT_IDLE,
            LlmErrorCategory.TIMEOUT_TOTAL,
            LlmErrorCategory.UPSTREAM_5XX,
            LlmErrorCategory.UPSTREAM_OVERLOADED
    );

    private final LlmProperties.Retry config;

    public LlmRetryPolicy(LlmProperties properties) {
        this.config = properties.retry();
    }

    public boolean retryable(LlmError error) {
        return error != null && RETRYABLE.contains(error.category());
    }

    public long backoffMs(int failedAttempt) {
        double base = config.initialBackoffMs() * Math.pow(2, Math.max(0, failedAttempt - 1));
        double capped = Math.min(config.maxBackoffMs(), base);
        double jitter = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * config.jitterRatio();
        return Math.max(0, Math.round(capped * jitter));
    }

    public int maxAttempts() {
        return Math.max(1, config.maxAttempts());
    }
}
