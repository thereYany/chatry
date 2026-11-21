package com.wherelee.base.modules.llm.call;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LlmCallRepository {

    private final JdbcTemplate jdbc;

    public LlmCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsByRequestId(String requestId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_llm_call_record WHERE request_id = ?", Integer.class, requestId);
        return count != null && count > 0;
    }

    public void insert(LlmCallRecord r) {
        jdbc.update("""
                INSERT INTO t_llm_call_record
                    (request_id, tenant_id, app_id, user_id, operation, provider, deployment,
                     model_requested, model_served, stream, attempt_count, status,
                     error_category, provider_code, retryable, billable,
                     input_tokens, output_tokens, cached_read_tokens, cached_write_tokens,
                     reasoning_tokens, usage_source, latency_ms, ttft_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, r.requestId(), r.tenantId(), r.appId(), r.userId(), r.operation(), r.provider(),
                r.deployment(), r.modelRequested(), r.modelServed(), r.stream(), r.attemptCount(),
                r.status(), r.errorCategory(), r.providerCode(), r.retryable(), r.billable(),
                r.inputTokens(), r.outputTokens(), r.cachedReadTokens(), r.cachedWriteTokens(),
                r.reasoningTokens(), r.usageSource(), r.latencyMs(), r.ttftMs());
    }
}
