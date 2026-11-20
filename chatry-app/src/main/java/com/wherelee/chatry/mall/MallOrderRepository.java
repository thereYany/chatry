package com.wherelee.chatry.mall;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 商城订单仓储。 */
@Repository
public class MallOrderRepository {

    private static final RowMapper<MallOrder> MAPPER = MallOrderRepository::map;
    private static final RowMapper<ExpiredRow> EXPIRED_MAPPER = (rs, rowNum) -> new ExpiredRow(
            rs.getLong("tenant_id"), rs.getString("order_no"), rs.getLong("sku_id"));

    private final JdbcTemplate jdbc;

    public MallOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long tenantId, String orderNo, long userId, long skuId, String skuTitle,
                       long amountCents, long creditCents, boolean flashSale, String limitKey,
                       Instant expireAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_mall_order
                        (tenant_id, order_no, user_id, sku_id, sku_title, amount_cents, credit_cents,
                         status, flash_sale, limit_key, expire_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, orderNo);
            ps.setLong(3, userId);
            ps.setLong(4, skuId);
            ps.setString(5, skuTitle);
            ps.setLong(6, amountCents);
            ps.setLong(7, creditCents);
            ps.setBoolean(8, flashSale);
            ps.setString(9, limitKey);
            ps.setTimestamp(10, Timestamp.from(expireAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("订单创建失败：未返回主键");
        }
        return key.longValue();
    }

    public Optional<MallOrder> findByNo(long tenantId, String orderNo) {
        return jdbc.query("""
                SELECT * FROM t_mall_order WHERE tenant_id = ? AND order_no = ?
                """, MAPPER, tenantId, orderNo).stream().findFirst();
    }

    public List<MallOrder> listByUser(long tenantId, long userId, int limit) {
        return jdbc.query("""
                SELECT * FROM t_mall_order
                WHERE tenant_id = ? AND user_id = ?
                ORDER BY id DESC LIMIT ?
                """, MAPPER, tenantId, userId, limit);
    }

    public int countActive(long tenantId, long userId, long skuId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_mall_order
                WHERE tenant_id = ? AND user_id = ? AND sku_id = ?
                  AND status IN ('CREATED', 'PAYING', 'PAID')
                """, Integer.class, tenantId, userId, skuId);
        return count == null ? 0 : count;
    }

    public int markPaying(long tenantId, String orderNo, String paymentNo) {
        return jdbc.update("""
                UPDATE t_mall_order SET status = 'PAYING', payment_no = ?
                WHERE tenant_id = ? AND order_no = ? AND status = 'CREATED'
                """, paymentNo, tenantId, orderNo);
    }

    public int markPaid(long tenantId, String orderNo, Instant paidAt) {
        return jdbc.update("""
                UPDATE t_mall_order SET status = 'PAID', paid_at = ?
                WHERE tenant_id = ? AND order_no = ? AND status IN ('CREATED', 'PAYING')
                """, Timestamp.from(paidAt), tenantId, orderNo);
    }

    /** 支付成功撞上关单：不改回 PAID（额度不能入账），标记为待退款/人工处理。 */
    public int markPaidAfterClose(long tenantId, String orderNo, Instant paidAt) {
        return jdbc.update("""
                UPDATE t_mall_order SET status = 'PAID_AFTER_CLOSE', paid_at = ?
                WHERE tenant_id = ? AND order_no = ? AND status IN ('CLOSED', 'EXPIRED')
                """, Timestamp.from(paidAt), tenantId, orderNo);
    }

    public int closeIfOpen(long tenantId, String orderNo) {
        return jdbc.update("""
                UPDATE t_mall_order SET status = 'CLOSED'
                WHERE tenant_id = ? AND order_no = ? AND status IN ('CREATED', 'PAYING')
                """, tenantId, orderNo);
    }

    /**
     * 跨租户扫描过期未支付订单（仅在系统作用域内调用）。
     *
     * <p>SQL 不带 tenant_id：系统作用域下拦截器跳过自动注入，才能一次扫全部租户。</p>
     */
    public List<ExpiredRow> findExpiredAcrossTenants(Instant now, int limit) {
        return jdbc.query("""
                SELECT tenant_id, order_no, sku_id FROM t_mall_order
                WHERE status IN ('CREATED', 'PAYING') AND expire_at < ?
                ORDER BY id LIMIT ?
                """, EXPIRED_MAPPER, Timestamp.from(now), limit);
    }

    public record ExpiredRow(long tenantId, String orderNo, long skuId) {
    }

    private static MallOrder map(ResultSet rs, int rowNum) throws SQLException {
        return new MallOrder(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("order_no"),
                rs.getLong("user_id"),
                rs.getLong("sku_id"),
                rs.getString("sku_title"),
                rs.getLong("amount_cents"),
                rs.getLong("credit_cents"),
                rs.getString("status"),
                rs.getString("payment_no"),
                rs.getBoolean("flash_sale"),
                toInstant(rs, "expire_at"),
                toInstant(rs, "paid_at"),
                toInstant(rs, "created_at"));
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
