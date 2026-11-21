package com.wherelee.base.modules.llm.resilience;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 重试预算：按 provider 维护 token bucket。初始容量 10，每次重试 -1，每次成功 +0.1。
 * 预算耗尽时不再重试，直接返回失败，防止下游故障期间重试流量放大。
 */
@Component
public class LlmRetryBudget {

    private static final double CAPACITY = 10.0;
    private static final double SUCCESS_CREDIT = 0.1;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean tryRetry(String providerId) {
        Bucket bucket = buckets.computeIfAbsent(providerId, ignored -> new Bucket());
        return bucket.tryRetry();
    }

    public void onSuccess(String providerId) {
        buckets.computeIfAbsent(providerId, ignored -> new Bucket()).onSuccess();
    }

    private static final class Bucket {
        private double tokens = CAPACITY;

        synchronized boolean tryRetry() {
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }

        synchronized void onSuccess() {
            tokens = Math.min(CAPACITY, tokens + SUCCESS_CREDIT);
        }
    }
}
