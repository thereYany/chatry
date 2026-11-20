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

/** 商城 SKU 仓储。库存扣减/回补用条件更新，天然防超卖。 */
@Repository
public class MallSkuRepository {

    private static final RowMapper<MallSku> MAPPER = MallSkuRepository::map;

    private final JdbcTemplate jdbc;

    public MallSkuRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MallSku> listOnSale(long tenantId) {
        return jdbc.query("""
                SELECT * FROM t_mall_sku
                WHERE tenant_id = ? AND status = 'ON_SALE'
                ORDER BY sort, id
                """, MAPPER, tenantId);
    }

    public List<MallSku> listAll(long tenantId) {
        return jdbc.query("""
                SELECT * FROM t_mall_sku WHERE tenant_id = ? ORDER BY sort, id
                """, MAPPER, tenantId);
    }

    public Optional<MallSku> findById(long tenantId, long id) {
        return jdbc.query("""
                SELECT * FROM t_mall_sku WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    public long insert(MallSku sku) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_mall_sku
                        (tenant_id, code, title, description, price_cents, credit_cents, stock,
                         per_user_limit, flash_sale, flash_price_cents, flash_start_at, flash_end_at,
                         status, sort)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sku.tenantId());
            ps.setString(2, sku.code());
            ps.setString(3, sku.title());
            ps.setString(4, sku.description());
            ps.setLong(5, sku.priceCents());
            ps.setLong(6, sku.creditCents());
            ps.setInt(7, sku.stock());
            ps.setInt(8, sku.perUserLimit());
            ps.setBoolean(9, sku.flashSale());
            ps.setObject(10, sku.flashPriceCents());
            ps.setTimestamp(11, sku.flashStartAt() == null ? null : Timestamp.from(sku.flashStartAt()));
            ps.setTimestamp(12, sku.flashEndAt() == null ? null : Timestamp.from(sku.flashEndAt()));
            ps.setString(13, sku.status());
            ps.setInt(14, sku.sort());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("SKU 创建失败：未返回主键");
        }
        return key.longValue();
    }

    public int updateStatus(long tenantId, long id, String status) {
        return jdbc.update("""
                UPDATE t_mall_sku SET status = ? WHERE tenant_id = ? AND id = ?
                """, status, tenantId, id);
    }

    /** 扣 1 件库存；无限量（-1）的 SKU 不会走到这里。返回 0 表示已售罄。 */
    public int decrementStock(long tenantId, long id) {
        return jdbc.update("""
                UPDATE t_mall_sku SET stock = stock - 1
                WHERE tenant_id = ? AND id = ? AND stock > 0
                """, tenantId, id);
    }

    /** 回补 1 件库存（订单关单/创建失败时）。 */
    public int restoreStock(long tenantId, long id) {
        return jdbc.update("""
                UPDATE t_mall_sku SET stock = stock + 1
                WHERE tenant_id = ? AND id = ? AND stock >= 0
                """, tenantId, id);
    }

    private static MallSku map(ResultSet rs, int rowNum) throws SQLException {
        return new MallSku(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("code"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getLong("price_cents"),
                rs.getLong("credit_cents"),
                rs.getInt("stock"),
                rs.getInt("per_user_limit"),
                rs.getBoolean("flash_sale"),
                rs.getObject("flash_price_cents") == null ? null : rs.getLong("flash_price_cents"),
                toInstant(rs, "flash_start_at"),
                toInstant(rs, "flash_end_at"),
                rs.getString("status"),
                rs.getInt("sort"));
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
