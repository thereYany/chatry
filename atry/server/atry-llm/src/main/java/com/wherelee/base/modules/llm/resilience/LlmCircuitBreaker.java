package com.wherelee.base.modules.llm.resilience;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.llm.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * provider × model 粒度熔断器（CLOSED/OPEN/HALF_OPEN）。
 *
 * <p>只统计 5xx/超时/连接失败/overloaded；4xx 不计入。OPEN 期间快速失败、不占线程；
 * OPEN 到期进入 HALF_OPEN，限量探测，成功关闭、失败重新打开。</p>
 */
@Component
public class LlmCircuitBreaker {

    private static final Set<LlmErrorCategory> COUNTED = EnumSet.of(
            LlmErrorCategory.UPSTREAM_5XX,
            LlmErrorCategory.UPSTREAM_OVERLOADED,
            LlmErrorCategory.TIMEOUT_CONNECT,
            LlmErrorCategory.TIMEOUT_FIRST_TOKEN,
            LlmErrorCategory.TIMEOUT_IDLE,
            LlmErrorCategory.TIMEOUT_TOTAL
    );

    private final LlmProperties.Circuit config;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public LlmCircuitBreaker(LlmProperties properties) {
        this.config = properties.circuit();
    }

    /** 在调用前申请许可；OPEN/HALF_OPEN 无探测额度时快速拒绝。 */
    public void acquire(String key) {
        State state = states.computeIfAbsent(key, ignored -> new State());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.mode == Mode.OPEN) {
                if (now < state.openUntil) {
                    throw circuitOpen(key);
                }
                state.mode = Mode.HALF_OPEN;
                state.halfOpenRemaining = Math.max(1, config.halfOpenProbes());
            }
            if (state.mode == Mode.HALF_OPEN) {
                if (state.halfOpenRemaining <= 0) {
                    throw circuitOpen(key);
                }
                state.halfOpenRemaining--;
            }
        }
    }

    public void recordSuccess(String key) {
        recordSuccess(key, 0);
    }

    /** 成功回调，同时累计慢调用；慢调用率超阈值时提前打开（抢在下游真崩前降载）。 */
    public void recordSuccess(String key, long latencyMs) {
        State state = states.computeIfAbsent(key, ignored -> new State());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.mode == Mode.HALF_OPEN) {
                state.mode = Mode.CLOSED;
                state.calls = 0;
                state.failures = 0;
                state.slowCalls = 0;
                state.openUntil = 0;
                return;
            }
            if (state.mode == Mode.OPEN) {
                return;
            }
            state.calls++;
            if (config.slowCallMs() > 0 && latencyMs >= config.slowCallMs()) {
                state.slowCalls++;
            }
            if (state.calls >= Math.max(1, config.minimumCalls())
                    && state.slowCalls * 100.0 / state.calls >= config.slowCallRateThreshold()) {
                open(state, now);
            }
        }
    }

    public void recordFailure(String key, LlmError error) {
        if (error == null || !COUNTED.contains(error.category())) {
            return;
        }
        State state = states.computeIfAbsent(key, ignored -> new State());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.mode == Mode.HALF_OPEN) {
                open(state, now);
                return;
            }
            if (state.mode == Mode.OPEN) {
                return;
            }
            state.calls++;
            state.failures++;
            if (state.calls >= Math.max(1, config.minimumCalls())
                    && state.failures * 100.0 / state.calls >= config.failureRateThreshold()) {
                open(state, now);
            }
        }
    }

    public boolean isOpen(String key) {
        State state = states.get(key);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.mode == Mode.OPEN && System.currentTimeMillis() < state.openUntil;
        }
    }

    private void open(State state, long now) {
        state.mode = Mode.OPEN;
        state.openUntil = now + Math.max(1, config.openMs());
        state.calls = 0;
        state.failures = 0;
        state.slowCalls = 0;
    }

    private LlmException circuitOpen(String key) {
        return new LlmException(new LlmError(LlmErrorCategory.UPSTREAM_OVERLOADED, "CIRCUIT_OPEN",
                false, false, "熔断器打开，快速失败：" + key));
    }

    private enum Mode { CLOSED, OPEN, HALF_OPEN }

    private static final class State {
        private Mode mode = Mode.CLOSED;
        private int calls;
        private int failures;
        private int slowCalls;
        private long openUntil;
        private int halfOpenRemaining;
    }
}
