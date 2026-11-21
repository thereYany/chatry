package com.wherelee.base.modules.payment.outbox;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 事务性发件箱：与支付状态变更同事务写入，投递器异步发出。
 *
 * <p>为什么必须 outbox：回调里"改库 + 发消息"是双写，进程在两步之间崩溃就会丢事件。
 * 同事务写 outbox 后，事件与支付状态同生共死，投递失败只是延迟，不会丢。</p>
 */
@Repository
public class PaymentOutboxRepository {

    /** 所有租户一起扫描（投递器是平台级 worker，不是某个租户的请求）。 */
    private static final String ALL_TENANTS = "tenant_id >= 0";

    private static final RowMapper<OutboxRow> MAPPER = (rs, rowNum) -> new OutboxRow(
            rs.getLong("id"),
            rs.getString("event_type"),
            rs.getString("aggregate_no"),
            rs.getString("payload"),
            rs.getInt("attempts"),
            rs.getLong("tenant_id")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PaymentOutboxRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 在调用方事务内追加一条待投递事件。 */
    public void append(PaymentSettledEvent event) {
        try {
            // 显式写租户：系统作用域会跳过 SQL 自动注入，回调/查单路径已回填支付单租户；
            // 请求线程即登录租户。这样无论线程处于什么作用域，事件归属都正确。
            jdbc.update("""
                    INSERT INTO t_payment_outbox
                        (tenant_id, event_type, aggregate_no, payload, status, next_at)
                    VALUES (?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP(3))
                    """, TenantContext.current(), event.eventType(), event.paymentNo(),
                    objectMapper.writeValueAsString(event));
        } catch (JacksonException e) {
            throw new IllegalStateException("支付事件序列化失败 paymentNo=" + event.paymentNo(), e);
        }
    }

    /** 抢占一批到期事件：先改 SENDING 再返回，避免多实例/重入重复投递。 */
    public List<OutboxRow> claimDue(int limit) {
        List<OutboxRow> rows = jdbc.query("""
                SELECT id, tenant_id, event_type, aggregate_no, payload, attempts
                FROM t_payment_outbox
                WHERE tenant_id >= 0 AND status = 'PENDING' AND next_at <= CURRENT_TIMESTAMP(3)
                ORDER BY id
                LIMIT ?
                """, MAPPER, limit);
        return rows.stream().filter(row -> jdbc.update("""
                UPDATE t_payment_outbox
                SET status = 'SENDING', next_at = CURRENT_TIMESTAMP(3)
                WHERE id = ? AND status = 'PENDING' AND tenant_id >= 0
                """, row.id()) == 1).toList();
    }

    /**
     * 回收卡在 SENDING 的事件（进程在“抢占后、投递前”崩溃会留下永久 SENDING）。
     * 只回收超过阈值的行，避免误抢正常投递中的事件。
     */
    public int reclaimStuck(int stuckAfterSeconds) {
        return jdbc.update("""
                UPDATE t_payment_outbox
                SET status = 'PENDING', attempts = attempts + 1,
                    last_error = 'reclaimed from SENDING (crash recovery)',
                    next_at = CURRENT_TIMESTAMP(3)
                WHERE status = 'SENDING'
                  AND next_at <= TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(3))
                """, Math.max(5, stuckAfterSeconds));
    }

    public PaymentSettledEvent payload(OutboxRow row) {
        try {
            return objectMapper.readValue(row.payload(), PaymentSettledEvent.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("支付事件反序列化失败 id=" + row.id(), e);
        }
    }

    public void markSent(Long id) {
        jdbc.update("""
                UPDATE t_payment_outbox
                SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(3),
                    attempts = attempts + 1, last_error = NULL
                WHERE id = ?
                """, id);
    }

    public void reschedule(Long id, int attempts, int backoffSeconds, String error) {
        jdbc.update("""
                UPDATE t_payment_outbox
                SET status = 'PENDING', attempts = ?, last_error = ?,
                    next_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3))
                WHERE id = ?
                """, attempts, truncate(error), backoffSeconds, id);
    }

    public void markDead(Long id, String error) {
        jdbc.update("""
                UPDATE t_payment_outbox
                SET status = 'DEAD', last_error = ?, attempts = attempts + 1
                WHERE id = ?
                """, truncate(error), id);
    }

    /** 积压在途（PENDING + SENDING）数量，用于指标与告警。 */
    public int countInFlight() {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_payment_outbox WHERE tenant_id >= 0 AND status IN ('PENDING','SENDING')",
                Integer.class);
        return value == null ? 0 : value;
    }

    public int countDead() {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_payment_outbox WHERE tenant_id >= 0 AND status = 'DEAD'",
                Integer.class);
        return value == null ? 0 : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    /** 发件箱一行。tenantId 用于投递时回填租户上下文（listener 的 SQL 需要正确租户）。 */
    public record OutboxRow(Long id, String eventType, String aggregateNo, String payload,
                            int attempts, long tenantId) {
    }
}
