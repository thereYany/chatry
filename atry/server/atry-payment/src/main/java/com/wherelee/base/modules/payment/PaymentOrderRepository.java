package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 支付单仓储（t_payment_order，支付域自有的聚合）。
 */
@Repository
public class PaymentOrderRepository {

    private static final RowMapper<PaymentOrderView> MAPPER = (rs, rowNum) -> new PaymentOrderView(
            rs.getString("payment_no"),
            rs.getString("biz_no"),
            rs.getString("app_id"),
            rs.getString("biz_type"),
            rs.getString("subject"),
            rs.getLong("amount_cents"),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getString("channel"),
            rs.getString("provider_trade_no"),
            rs.getTimestamp("expire_at").toLocalDateTime(),
            rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toLocalDateTime(),
            rs.getLong("tenant_id")
    );

    private final JdbcTemplate jdbc;

    public PaymentOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按业务单号查支付单。
     *
     * <p>租户条件：{@code currentOrNull()} 为 null（系统作用域且未回填租户）时跨租户定位，
     * 有值（请求线程 / 已回填的系统作用域）时只查本租户，避免请求路径跨租户读取。</p>
     */
    public Optional<PaymentOrderView> findByBizNo(String bizNo) {
        Long tenantId = TenantContext.currentOrNull();
        return jdbc.query("""
                SELECT payment_no, biz_no, app_id, biz_type, subject, amount_cents, currency,
                       status, channel, provider_trade_no, expire_at, paid_at, tenant_id
                FROM t_payment_order WHERE biz_no = ?
                  AND (tenant_id = ? OR ? IS NULL)
                """, MAPPER, bizNo, tenantId, tenantId).stream().findFirst();
    }

    /** 按 (bizType, bizNo) 查支付单；租户条件同 {@link #findByBizNo(String)}。 */
    public Optional<PaymentOrderView> findByBiz(String bizType, String bizNo) {
        Long tenantId = TenantContext.currentOrNull();
        return jdbc.query("""
                SELECT payment_no, biz_no, app_id, biz_type, subject, amount_cents, currency,
                       status, channel, provider_trade_no, expire_at, paid_at, tenant_id
                FROM t_payment_order WHERE biz_type = ? AND biz_no = ?
                  AND (tenant_id = ? OR ? IS NULL)
                """, MAPPER, bizType, bizNo, tenantId, tenantId).stream().findFirst();
    }

    /** 按渠道流水号定位支付单（回调场景）；租户条件同 {@link #findByBizNo(String)}。 */
    public Optional<PaymentOrderView> findByProviderTradeNo(String channel, String providerTradeNo) {
        Long tenantId = TenantContext.currentOrNull();
        return jdbc.query("""
                SELECT payment_no, biz_no, app_id, biz_type, subject, amount_cents, currency,
                       status, channel, provider_trade_no, expire_at, paid_at, tenant_id
                FROM t_payment_order WHERE channel = ? AND provider_trade_no = ?
                  AND (tenant_id = ? OR ? IS NULL)
                """, MAPPER, channel, providerTradeNo, tenantId, tenantId).stream().findFirst();
    }

    /** 按支付单号查支付单；租户条件同 {@link #findByBizNo(String)}。 */
    public Optional<PaymentOrderView> findByPaymentNo(String paymentNo) {
        Long tenantId = TenantContext.currentOrNull();
        return jdbc.query("""
                SELECT payment_no, biz_no, app_id, biz_type, subject, amount_cents, currency,
                       status, channel, provider_trade_no, expire_at, paid_at, tenant_id
                FROM t_payment_order WHERE payment_no = ?
                  AND (tenant_id = ? OR ? IS NULL)
                """, MAPPER, paymentNo, tenantId, tenantId).stream().findFirst();
    }

    /** 幂等创建：同一 (app_id,biz_type,biz_no) 只会有一条支付单。 */
    public void insertIfAbsent(PaymentOrderView order, long tenantId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO t_payment_order
                    (payment_no, app_id, biz_type, biz_no, tenant_id, subject, amount_cents, currency,
                     status, channel, provider_trade_no, expire_at, source_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE payment_no = payment_no
                """, order.paymentNo(), order.appId(), order.bizType(), order.bizNo(), tenantId,
                order.subject(), order.amountCents(), order.currency(), order.status(),
                order.channel(), order.providerTradeNo(),
                Timestamp.valueOf(order.expireAt()), Timestamp.valueOf(now));
    }

    public int markPaying(String paymentNo, String channel, String providerTradeNo) {
        return jdbc.update("""
                UPDATE t_payment_order
                SET status = 'PAYING', channel = ?, provider_trade_no = ?,
                    source_updated_at = CURRENT_TIMESTAMP(3), version = version + 1
                WHERE payment_no = ? AND status IN ('CREATED', 'PAYING')
                """, channel, providerTradeNo, paymentNo);
    }

    public int markPaid(String paymentNo, String channel, String providerTradeNo) {
        return jdbc.update("""
                UPDATE t_payment_order
                SET status = 'PAID', channel = ?, provider_trade_no = ?, paid_at = CURRENT_TIMESTAMP(3),
                    source_updated_at = CURRENT_TIMESTAMP(3), version = version + 1
                WHERE payment_no = ? AND status IN ('CREATED', 'PAYING')
                """, channel, providerTradeNo, paymentNo);
    }

    public int markFailed(String paymentNo) {
        return jdbc.update("""
                UPDATE t_payment_order
                SET status = 'FAILED', source_updated_at = CURRENT_TIMESTAMP(3), version = version + 1
                WHERE payment_no = ? AND status IN ('CREATED', 'PAYING')
                """, paymentNo);
    }

    public int markClosed(String paymentNo) {
        return jdbc.update("""
                UPDATE t_payment_order
                SET status = 'CLOSED', source_updated_at = CURRENT_TIMESTAMP(3), version = version + 1
                WHERE payment_no = ? AND status IN ('CREATED', 'PAYING', 'FAILED')
                """, paymentNo);
    }

    public int markRefunding(String paymentNo) {
        return jdbc.update("""
                UPDATE t_payment_order SET status = 'REFUNDING', source_updated_at = CURRENT_TIMESTAMP(3)
                WHERE payment_no = ? AND status IN ('PAID', 'REFUNDING')
                """, paymentNo);
    }

    public int markRefunded(String paymentNo) {
        return jdbc.update("""
                UPDATE t_payment_order SET status = 'REFUNDED', source_updated_at = CURRENT_TIMESTAMP(3)
                WHERE payment_no = ? AND status IN ('PAID', 'REFUNDING', 'REFUNDED')
                """, paymentNo);
    }

    public int markRefundFailed(String paymentNo) {
        return jdbc.update("""
                UPDATE t_payment_order SET status = 'REFUND_FAILED', source_updated_at = CURRENT_TIMESTAMP(3)
                WHERE payment_no = ? AND status IN ('REFUNDING', 'PAID', 'REFUND_FAILED')
                """, paymentNo);
    }

    /**
     * 主动查单：支付中且已超过查询窗口的支付单。
     *
     * <p>平台级扫描（系统作用域 job）：显式 {@code tenant_id >= 0} 跨租户，不按租户过滤。</p>
     */
    public List<PaymentOrderView> findPayingOlderThan(int olderThanSeconds, int limit) {
        return jdbc.query("""
                SELECT payment_no, biz_no, app_id, biz_type, subject, amount_cents, currency,
                       status, channel, provider_trade_no, expire_at, paid_at, tenant_id
                FROM t_payment_order
                WHERE tenant_id >= 0 AND status = 'PAYING'
                  AND updated_at <= TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(3))
                ORDER BY updated_at, id
                LIMIT ?
                """, MAPPER, olderThanSeconds, limit);
    }
}
