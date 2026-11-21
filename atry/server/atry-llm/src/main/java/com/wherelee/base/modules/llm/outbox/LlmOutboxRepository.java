package com.wherelee.base.modules.llm.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Repository
public class LlmOutboxRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LlmOutboxRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record OutboxRow(long id, String aggregateId, String payload, int attempts) {
    }

    public List<OutboxRow> findDue(int limit) {
        return jdbc.query("""
                        SELECT id, aggregate_id, CAST(payload AS CHAR) AS payload, attempts
                        FROM t_llm_outbox
                        WHERE status = 'PENDING' AND next_at <= CURRENT_TIMESTAMP(3)
                        ORDER BY id LIMIT ?
                        """,
                (rs, rowNum) -> new OutboxRow(rs.getLong("id"), rs.getString("aggregate_id"),
                        rs.getString("payload"), rs.getInt("attempts")),
                Math.max(1, limit));
    }

    public void markSent(long id) {
        jdbc.update("UPDATE t_llm_outbox SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(3) WHERE id = ?", id);
    }

    public void scheduleRetry(long id, int attempts, String error) {
        int nextAttempt = attempts + 1;
        String status = nextAttempt >= 10 ? "DEAD" : "PENDING";
        long delaySeconds = Math.min(60, 1L << Math.min(6, nextAttempt));
        jdbc.update("""
                        UPDATE t_llm_outbox
                        SET status = ?, attempts = ?, last_error = ?, next_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND)
                        WHERE id = ?
                        """,
                status, nextAttempt, error == null ? null : error.substring(0, Math.min(500, error.length())),
                delaySeconds, id);
    }

    public void append(String aggregateId, String eventType, Object payload) {
        jdbc.update("""
                INSERT INTO t_llm_outbox (aggregate_id, event_type, payload, status, next_at)
                VALUES (?, ?, ?, 'PENDING', CURRENT_TIMESTAMP(3))
                """, aggregateId, eventType, objectMapper.writeValueAsString(payload));
    }
}
