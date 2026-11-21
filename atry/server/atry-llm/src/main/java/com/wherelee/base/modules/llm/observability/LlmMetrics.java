package com.wherelee.base.modules.llm.observability;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/** LLM 指标（低基数标签：provider/model/status，禁止放 tenant/user/request/prompt）。 */
@Component
public class LlmMetrics {

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void success(String provider, String model, boolean stream, long latencyMs, LlmUsage usage,
                        Long ttftMs) {
        calls(provider, model, stream, "SUCCESS");
        duration(provider, model, stream, latencyMs);
        if (usage != null) {
            tokens(provider, model, "input", usage.inputTokens());
            tokens(provider, model, "output", usage.outputTokens());
            tokens(provider, model, "cached_read", usage.cachedReadTokens());
            tokens(provider, model, "reasoning", usage.reasoningTokens());
        }
        if (stream && ttftMs != null) {
            Timer.builder("atry.llm.ttft")
                    .tag("provider", provider).tag("model", model)
                    .register(registry).record(Duration.ofMillis(Math.max(0, ttftMs)));
        }
    }

    public void failure(String provider, String model, boolean stream, LlmError error, long latencyMs) {
        calls(provider, model, stream, "FAILED");
        duration(provider, model, stream, latencyMs);
        if (error != null) {
            Counter.builder("atry.llm.errors")
                    .tag("provider", provider)
                    .tag("category", error.category().name())
                    .tag("retryable", Boolean.toString(error.retryable()))
                    .register(registry).increment();
        }
    }

    public void retry(String provider, boolean attempted) {
        Counter.builder("atry.llm.retries")
                .tag("provider", provider)
                .tag("outcome", attempted ? "attempted" : "budget_exhausted")
                .register(registry).increment();
    }

    /**
     * 成本归因：按 tenant × model 累加（token → 金额在 llm 侧用 metering 价格路径换算）。
     * tenant 只出现在成本类指标上，其余指标保持低基数。
     */
    public void cost(long tenantId, String model, BigDecimal cost) {
        if (cost == null || cost.signum() <= 0) {
            return;
        }
        Counter.builder("atry.llm.cost")
                .tag("tenant", Long.toString(tenantId))
                .tag("model", model == null ? "unknown" : model)
                .register(registry).increment(cost.doubleValue());
    }

    public void rejected(String scope) {
        Counter.builder("atry.llm.ratelimit.rejected").tag("scope", scope).register(registry).increment();
    }

    /** 预算守卫触发计数：budgetRef × action(REJECT/DEGRADE)。 */
    public void budgetExceeded(String budgetRef, String action) {
        Counter.builder("atry.llm.budget.exceeded")
                .tag("budget", budgetRef == null ? "unknown" : budgetRef)
                .tag("action", action)
                .register(registry).increment();
    }

    private void calls(String provider, String model, boolean stream, String status) {
        Counter.builder("atry.llm.calls")
                .tag("provider", provider)
                .tag("model", model)
                .tag("operation", "chat")
                .tag("stream", Boolean.toString(stream))
                .tag("status", status)
                .register(registry).increment();
    }

    private void duration(String provider, String model, boolean stream, long latencyMs) {
        Timer.builder("atry.llm.duration")
                .tag("provider", provider)
                .tag("model", model)
                .tag("stream", Boolean.toString(stream))
                .register(registry).record(Duration.ofMillis(Math.max(0, latencyMs)));
    }

    private void tokens(String provider, String model, String type, long count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("atry.llm.tokens")
                .tag("provider", provider)
                .tag("model", model)
                .tag("token_type", type)
                .register(registry).increment(count);
    }
}
