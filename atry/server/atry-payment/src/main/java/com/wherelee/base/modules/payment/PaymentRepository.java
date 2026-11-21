package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class PaymentRepository {

    private static final RowMapper<PaymentAttemptRecord> ATTEMPT_MAPPER = (rs, rowNum) -> new PaymentAttemptRecord(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getInt("attempt_no"),
            rs.getString("channel"),
            rs.getString("status"),
            rs.getString("provider_trade_no"),
            rs.getString("provider_status"),
            rs.getLong("provider_amount_cents"),
            rs.getString("request_digest"),
            rs.getString("failure_code"),
            rs.getString("failure_message"),
            rs.getString("mock_scenario"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            toLocal(rs.getTimestamp("finished_at"))
    );

    private static final RowMapper<PaymentEventRecord> EVENT_MAPPER = (rs, rowNum) -> new PaymentEventRecord(
            rs.getLong("id"),
            rs.getString("provider"),
            rs.getString("event_key"),
            rs.getLong("order_id"),
            rs.getString("order_no"),
            rs.getString("event_type"),
            rs.getLong("amount_cents"),
            rs.getString("process_status"),
            rs.getString("error_message"),
            rs.getTimestamp("received_at").toLocalDateTime(),
            toLocal(rs.getTimestamp("processed_at"))
    );

    private static final RowMapper<RefundRecord> REFUND_MAPPER = (rs, rowNum) -> new RefundRecord(
            rs.getLong("id"),
            rs.getString("refund_no"),
            (Long) rs.getObject("order_id"),
            rs.getString("payment_no"),
            rs.getLong("amount_cents"),
            rs.getString("status"),
            rs.getInt("attempt_count"),
            toLocal(rs.getTimestamp("next_retry_at")),
            rs.getString("provider_refund_no"),
            rs.getString("reason"),
            rs.getLong("requested_by"),
            rs.getTimestamp("requested_at").toLocalDateTime(),
            toLocal(rs.getTimestamp("succeeded_at")),
            rs.getString("error_message")
    );

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int nextAttemptNo(Long orderId) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM t_payment_attempt WHERE order_id = ?
                """, Integer.class, orderId);
        return value == null ? 1 : value;
    }

    public Long createAttempt(Long orderId, String paymentNo, int attemptNo, String channel,
                              String providerTradeNo, long amountCents, String digest) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO t_payment_attempt
                        (tenant_id, order_id, payment_no, attempt_no, channel, status, provider_trade_no,
                         provider_status, provider_amount_cents, request_digest)
                    VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, 'CREATED', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, TenantContext.current());
            if (orderId == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, orderId);
            }
            statement.setString(3, paymentNo);
            statement.setInt(4, attemptNo);
            statement.setString(5, channel);
            statement.setString(6, providerTradeNo);
            statement.setLong(7, amountCents);
            statement.setString(8, digest);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建支付尝试后未返回 ID");
        }
        return key.longValue();
    }

    public Optional<PaymentAttemptRecord> findLatestAttempt(Long orderId) {
        return jdbc.query("""
                SELECT * FROM t_payment_attempt WHERE order_id = ?
                ORDER BY attempt_no DESC LIMIT 1
                """, ATTEMPT_MAPPER, orderId).stream().findFirst();
    }

    public int nextAttemptNoByPaymentNo(String paymentNo) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_no), 0) FROM t_payment_attempt WHERE payment_no = ?",
                Integer.class, paymentNo);
        return (max == null ? 0 : max) + 1;
    }

    public Optional<PaymentAttemptRecord> findLatestAttemptByPaymentNo(String paymentNo) {
        return jdbc.query("""
                SELECT * FROM t_payment_attempt WHERE payment_no = ?
                ORDER BY attempt_no DESC LIMIT 1
                """, ATTEMPT_MAPPER, paymentNo).stream().findFirst();
    }

    public Optional<PaymentAttemptRecord> findAttemptByProviderTradeNo(String providerTradeNo) {
        return jdbc.query("""
                SELECT * FROM t_payment_attempt WHERE provider_trade_no = ?
                ORDER BY id DESC LIMIT 1
                """, ATTEMPT_MAPPER, providerTradeNo).stream().findFirst();
    }

    public List<PaymentAttemptRecord> findAttemptsByPaymentNo(String paymentNo) {
        return jdbc.query("""
                SELECT * FROM t_payment_attempt WHERE payment_no = ?
                ORDER BY attempt_no, id
                """, ATTEMPT_MAPPER, paymentNo);
    }

    public List<PaymentAttemptRecord> findAttempts(Long orderId) {
        return jdbc.query("""
                SELECT * FROM t_payment_attempt WHERE order_id = ? ORDER BY attempt_no
                """, ATTEMPT_MAPPER, orderId);
    }

    public void updateProviderState(Long attemptId, String providerStatus, long providerAmountCents,
                                    String scenario) {
        jdbc.update("""
                UPDATE t_payment_attempt
                SET provider_status = ?, provider_amount_cents = ?,
                    mock_scenario = COALESCE(?, mock_scenario)
                WHERE id = ?
                """, providerStatus, providerAmountCents, scenario, attemptId);
    }

    /** 写渠道侧事实（Mock 收银台用）：provider_status / 金额 / 渠道流水号。 */
    public void updateProviderFact(Long attemptId, String status, long amountCents, String tradeNo) {
        jdbc.update("""
                UPDATE t_payment_attempt
                SET provider_status = ?, provider_amount_cents = ?, provider_trade_no = ?,
                    status = ?, finished_at = CURRENT_TIMESTAMP(3)
                WHERE id = ?
                """, status, amountCents, tradeNo, status, attemptId);
    }

    public void markAttemptSuccess(Long attemptId) {
        jdbc.update("""
                UPDATE t_payment_attempt
                SET status = 'SUCCESS', finished_at = CURRENT_TIMESTAMP(3)
                WHERE id = ? AND status = 'PROCESSING'
                """, attemptId);
    }

    public void markAttemptFailed(Long attemptId, String code, String message) {
        jdbc.update("""
                UPDATE t_payment_attempt
                SET status = 'FAILED', failure_code = ?, failure_message = ?,
                    finished_at = CURRENT_TIMESTAMP(3)
                WHERE id = ? AND status = 'PROCESSING'
                """, code, message, attemptId);
    }

    public boolean insertEvent(String provider, String eventKey, String paymentNo, String orderNo,
                               String eventType, long amountCents, String payloadDigest) {
        return jdbc.update("""
                INSERT IGNORE INTO t_payment_event
                    (tenant_id, provider, event_key, payment_no, order_no, event_type,
                     amount_cents, payload_digest, process_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED')
                """, TenantContext.current(), provider, eventKey, paymentNo, orderNo, eventType,
                amountCents, payloadDigest) > 0;
    }

    public Optional<PaymentEventRecord> findEvent(String provider, String eventKey) {
        return jdbc.query("""
                SELECT * FROM t_payment_event WHERE provider = ? AND event_key = ?
                """, EVENT_MAPPER, provider, eventKey).stream().findFirst();
    }

    public void markEventProcessed(Long eventId) {
        jdbc.update("""
                UPDATE t_payment_event
                SET process_status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP(3),
                    error_message = NULL
                WHERE id = ?
                """, eventId);
    }

    public void markEventFailed(Long eventId, String message) {
        jdbc.update("""
                UPDATE t_payment_event
                SET process_status = 'FAILED', processed_at = CURRENT_TIMESTAMP(3),
                    error_message = ?
                WHERE id = ?
                """, message, eventId);
    }

    public List<PaymentEventRecord> findEventsByPaymentNo(String paymentNo) {
        return jdbc.query("""
                SELECT * FROM t_payment_event WHERE payment_no = ?
                ORDER BY received_at, id
                """, EVENT_MAPPER, paymentNo);
    }

    public List<PaymentEventRecord> findEvents(Long orderId) {
        return jdbc.query("""
                SELECT * FROM t_payment_event WHERE order_id = ?
                ORDER BY received_at, id
                """, EVENT_MAPPER, orderId);
    }

    public Optional<RefundRecord> findRefundByNo(String refundNo) {
        return jdbc.query("""
                SELECT * FROM t_refund WHERE refund_no = ?
                """, REFUND_MAPPER, refundNo).stream().findFirst();
    }

    public List<RefundRecord> listRefundsByPayment(String paymentNo) {
        return jdbc.query("""
                SELECT * FROM t_refund WHERE payment_no = ? ORDER BY id
                """, REFUND_MAPPER, paymentNo);
    }

    public Optional<RefundRecord> findRefundById(Long id) {
        return jdbc.query("""
                SELECT * FROM t_refund WHERE id = ?
                """, REFUND_MAPPER, id).stream().findFirst();
    }

    public long sumRefundedCents(String paymentNo) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_cents), 0) FROM t_refund
                WHERE payment_no = ? AND status IN ('PROCESSING', 'SUCCEEDED')
                """, Long.class, paymentNo);
        return sum == null ? 0L : sum;
    }

    public Optional<RefundRecord> findRefundByOrder(Long orderId) {
        return jdbc.query("""
                SELECT * FROM t_refund WHERE order_id = ?
                """, REFUND_MAPPER, orderId).stream().findFirst();
    }

    public Long createRefund(String refundNo, Long orderId, String paymentNo, long amountCents,
                             String reason, Long requestedBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO t_refund
                        (tenant_id, refund_no, order_id, payment_no, amount_cents, status, reason, requested_by)
                    VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, TenantContext.current());
            statement.setString(2, refundNo);
            if (orderId == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, orderId);
            }
            statement.setString(4, paymentNo);
            statement.setLong(5, amountCents);
            statement.setString(6, reason);
            statement.setLong(7, requestedBy);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建退款后未返回 ID");
        }
        return key.longValue();
    }

    public int retryRefund(Long refundId) {
        return jdbc.update("""
                UPDATE t_refund
                SET status = 'PROCESSING', error_message = NULL, next_retry_at = NULL
                WHERE id = ? AND status = 'FAILED'
                """, refundId);
    }

    public int claimPendingRefund(Long refundId, int olderThanSeconds) {
        return jdbc.update("""
                UPDATE t_refund
                SET updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = ? AND status = 'PROCESSING'
                  AND updated_at <= TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(3))
                """, refundId, olderThanSeconds);
    }

    public void markRefundSucceeded(Long refundId, String providerRefundNo) {
        jdbc.update("""
                UPDATE t_refund
                SET status = 'SUCCEEDED', attempt_count = attempt_count + 1,
                    next_retry_at = NULL, provider_refund_no = ?,
                    succeeded_at = CURRENT_TIMESTAMP(3), error_message = NULL
                WHERE id = ? AND status = 'PROCESSING'
                """, providerRefundNo, refundId);
    }

    public void markRefundFailed(Long refundId, String message, LocalDateTime nextRetryAt) {
        jdbc.update("""
                UPDATE t_refund
                SET status = 'FAILED', attempt_count = attempt_count + 1,
                    next_retry_at = ?, error_message = ?
                WHERE id = ? AND status = 'PROCESSING'
                """, nextRetryAt == null ? null : Timestamp.valueOf(nextRetryAt), message, refundId);
    }

    public List<RefundRecord> findRetryableRefunds(int maxAttempts, int limit) {
        return jdbc.query("""
                SELECT * FROM t_refund
                WHERE status = 'FAILED'
                  AND attempt_count < ?
                  AND next_retry_at IS NOT NULL
                  AND next_retry_at <= CURRENT_TIMESTAMP(3)
                ORDER BY next_retry_at, id
                LIMIT ?
                """, REFUND_MAPPER, maxAttempts, limit);
    }

    public List<RefundRecord> findPendingRefunds(int olderThanSeconds, int limit) {
        return jdbc.query("""
                SELECT * FROM t_refund
                WHERE status = 'PROCESSING'
                  AND updated_at <= TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(3))
                ORDER BY updated_at, id
                LIMIT ?
                """, REFUND_MAPPER, olderThanSeconds, limit);
    }

    public long countSettlementUndeliveredBefore(LocalDateTime cutoff) {
        // 系统作用域内跨租户统计：NOT EXISTS 必须显式按同租户关联，避免别的租户的
        // 已投递 outbox 事件把本租户的未投递单"抵消"掉（跨租户串单）。
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_payment_order p
                WHERE p.status = 'PAID' AND p.paid_at <= ?
                  AND NOT EXISTS (SELECT 1 FROM t_payment_outbox o
                                  WHERE o.aggregate_no = p.payment_no
                                    AND o.tenant_id = p.tenant_id
                                    AND o.event_type = 'PAYMENT_SUCCEEDED'
                                    AND o.status = 'SENT')
                """, Long.class, Timestamp.valueOf(cutoff));
        return count == null ? 0 : count;
    }

    public long countPayingPaymentBefore(LocalDateTime cutoff) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_payment_order
                WHERE status = 'PAYING' AND updated_at <= ?
                """, Long.class, Timestamp.valueOf(cutoff));
        return count == null ? 0 : count;
    }

    public long countRefundRetryExhausted(int maxAttempts) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_refund
                WHERE status = 'FAILED' AND attempt_count >= ?
                """, Long.class, maxAttempts);
        return count == null ? 0 : count;
    }

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
