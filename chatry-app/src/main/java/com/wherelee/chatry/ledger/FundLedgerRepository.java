package com.wherelee.chatry.ledger;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 资金账本仓储：账户、交易、分录、期初回填与对账查询。 */
@Repository
public class FundLedgerRepository {

    private final JdbcTemplate jdbc;

    public FundLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long ensureAccount(long tenantId, String code, long ownerId, String currency,
                              String accountType, String normality) {
        Long existing = firstLong("""
                SELECT id FROM t_fund_ledger_account
                WHERE tenant_id = ? AND code = ? AND owner_id = ? AND currency = ?
                """, tenantId, code, ownerId, currency);
        if (existing != null) {
            return existing;
        }
        try {
            jdbc.update("""
                    INSERT INTO t_fund_ledger_account
                        (tenant_id, code, owner_id, currency, account_type, normality)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, tenantId, code, ownerId, currency, accountType, normality);
        } catch (DuplicateKeyException ignored) {
            // 并发建账户：回读
        }
        Long id = firstLong("""
                SELECT id FROM t_fund_ledger_account
                WHERE tenant_id = ? AND code = ? AND owner_id = ? AND currency = ?
                """, tenantId, code, ownerId, currency);
        if (id == null) {
            throw new IllegalStateException("资金账户创建失败: " + code + "/" + ownerId);
        }
        return id;
    }

    public boolean transactionExists(long tenantId, String eventKey) {
        Long count = firstLong("""
                SELECT COUNT(*) FROM t_fund_ledger_transaction
                WHERE tenant_id = ? AND event_key = ?
                """, tenantId, eventKey);
        return count != null && count > 0;
    }

    public long insertTransaction(long tenantId, String eventKey, String bizType, String bizNo,
                                  String description, LocalDateTime occurredAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_fund_ledger_transaction
                        (tenant_id, event_key, biz_type, biz_no, description, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, eventKey);
            ps.setString(3, bizType);
            ps.setString(4, bizNo);
            ps.setString(5, description);
            ps.setTimestamp(6, Timestamp.valueOf(occurredAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("资金交易创建失败：未返回主键");
        }
        return key.longValue();
    }

    public void insertEntry(long transactionId, long accountId, String direction,
                            BigDecimal amount, String currency) {
        jdbc.update("""
                INSERT INTO t_fund_ledger_entry (transaction_id, account_id, direction, amount, currency)
                VALUES (?, ?, ?, ?, ?)
                """, transactionId, accountId, direction, amount, currency);
    }

    public boolean hasLiabilityEntries(long tenantId, long userId) {
        Long count = firstLong("""
                SELECT COUNT(*)
                FROM t_fund_ledger_entry e
                         JOIN t_fund_ledger_transaction t ON t.id = e.transaction_id
                         JOIN t_fund_ledger_account a ON a.id = e.account_id
                WHERE t.tenant_id = ? AND a.tenant_id = ? AND a.code = 'CREDIT_LIABILITY'
                  AND a.owner_id = ?
                """, tenantId, tenantId, userId);
        return count != null && count > 0;
    }

    public UsageRow findUsage(String settleBizNo) {
        List<UsageRow> rows = jdbc.query("""
                SELECT a.tenant_id, a.user_id, l.amount, l.currency, l.biz_no
                FROM t_metering_ledger l
                         JOIN t_metering_account a ON a.id = l.account_id
                WHERE l.biz_no = ? AND l.biz_type = 'SETTLE'
                """, (rs, rowNum) -> new UsageRow(
                rs.getLong("tenant_id"), rs.getLong("user_id"), rs.getString("biz_no"),
                rs.getBigDecimal("amount"), rs.getString("currency")), settleBizNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<UsageRow> pendingUsage(LocalDateTime since, int limit) {
        return jdbc.query("""
                SELECT a.tenant_id, a.user_id, l.biz_no, l.amount, l.currency
                FROM t_metering_ledger l
                         JOIN t_metering_account a ON a.id = l.account_id
                         LEFT JOIN t_fund_ledger_transaction t
                                   ON t.tenant_id = a.tenant_id
                                       AND t.event_key = CONCAT('usage:', l.biz_no)
                WHERE l.biz_type = 'SETTLE'
                  AND l.created_at >= ?
                  AND t.id IS NULL
                ORDER BY l.id
                LIMIT ?
                """, (rs, rowNum) -> new UsageRow(
                rs.getLong("tenant_id"), rs.getLong("user_id"), rs.getString("biz_no"),
                rs.getBigDecimal("amount"), rs.getString("currency")),
                Timestamp.valueOf(since), limit);
    }

    public List<OpeningCredit> openingCredits() {
        return jdbc.query("""
                SELECT a.id, a.tenant_id, a.user_id, a.balance, a.currency
                FROM t_metering_account a
                         LEFT JOIN t_fund_ledger_transaction t
                                   ON t.tenant_id = a.tenant_id
                                       AND t.event_key = CONCAT('opening:credit:', a.id)
                WHERE a.app_id = 0
                  AND a.balance > 0
                  AND t.id IS NULL
                ORDER BY a.id
                """, (rs, rowNum) -> new OpeningCredit(
                rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("user_id"),
                rs.getBigDecimal("balance"), rs.getString("currency")));
    }

    public List<OpeningCash> openingCash() {
        return jdbc.query("""
                SELECT o.tenant_id, o.order_no, o.amount_cents
                FROM t_mall_order o
                         LEFT JOIN t_fund_ledger_transaction t
                                   ON t.tenant_id = o.tenant_id
                                       AND t.event_key = CONCAT('opening:cash:', o.order_no)
                         LEFT JOIN t_fund_ledger_transaction p
                                   ON p.tenant_id = o.tenant_id
                                       AND p.event_key = CONCAT('topup:', o.order_no)
                WHERE o.status IN ('PAID', 'PAID_AFTER_CLOSE')
                  AND t.id IS NULL
                  AND p.id IS NULL
                ORDER BY o.id
                """, (rs, rowNum) -> new OpeningCash(
                rs.getLong("tenant_id"), rs.getString("order_no"), rs.getLong("amount_cents")));
    }

    public BigDecimal balanceByCode(long tenantId, String code) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(
                    CASE a.normality
                        WHEN 'DEBIT' THEN CASE e.direction WHEN 'DEBIT' THEN e.amount ELSE -e.amount END
                        ELSE CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END
                    END), 0)
                FROM t_fund_ledger_entry e
                         JOIN t_fund_ledger_transaction t ON t.id = e.transaction_id
                         JOIN t_fund_ledger_account a ON a.id = e.account_id
                WHERE t.tenant_id = ? AND a.tenant_id = ? AND a.code = ?
                """, BigDecimal.class, tenantId, tenantId, code);
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal meteringBalance(long tenantId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(balance), 0) FROM t_metering_account
                WHERE tenant_id = ? AND app_id = 0
                """, BigDecimal.class, tenantId);
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal paidOrders(long tenantId) {
        BigDecimal cents = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_cents), 0) FROM t_mall_order
                WHERE tenant_id = ? AND status IN ('PAID', 'PAID_AFTER_CLOSE')
                """, BigDecimal.class, tenantId);
        return cents == null ? BigDecimal.ZERO : cents.movePointLeft(2);
    }

    public int unbalancedTransactions() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT transaction_id
                    FROM t_fund_ledger_entry
                    GROUP BY transaction_id
                    HAVING SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0
                ) x
                """, Integer.class);
        return count == null ? 0 : count;
    }

    public List<TransactionView> recentTransactions(long tenantId, int limit) {
        return jdbc.query("""
                SELECT t.id, t.event_key, t.biz_type, t.biz_no, t.description, t.occurred_at,
                       COALESCE(SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount ELSE 0 END), 0) AS debit,
                       COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE 0 END), 0) AS credit
                FROM t_fund_ledger_transaction t
                         LEFT JOIN t_fund_ledger_entry e ON e.transaction_id = t.id
                WHERE t.tenant_id = ?
                GROUP BY t.id, t.event_key, t.biz_type, t.biz_no, t.description, t.occurred_at
                ORDER BY t.id DESC
                LIMIT ?
                """, (rs, rowNum) -> new TransactionView(
                rs.getLong("id"), rs.getString("event_key"), rs.getString("biz_type"),
                rs.getString("biz_no"), rs.getString("description"),
                rs.getTimestamp("occurred_at").toLocalDateTime(),
                rs.getBigDecimal("debit"), rs.getBigDecimal("credit")), tenantId, limit);
    }

    public List<Long> tenantIds() {
        return jdbc.query("SELECT DISTINCT tenant_id FROM t_fund_ledger_account ORDER BY tenant_id",
                (rs, rowNum) -> rs.getLong(1));
    }

    public String meta(String key) {
        List<String> values = jdbc.query("SELECT meta_value FROM t_fund_ledger_meta WHERE meta_key = ?",
                (rs, rowNum) -> rs.getString(1), key);
        return values.isEmpty() ? null : values.get(0);
    }

    public void setMeta(String key, String value) {
        jdbc.update("""
                INSERT INTO t_fund_ledger_meta (meta_key, meta_value) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value)
                """, key, value);
    }

    public void upsertCheck(long tenantId, LocalDate date, FundLedgerSummary summary) {
        jdbc.update("""
                INSERT INTO t_fund_ledger_check
                    (tenant_id, check_date, liability, metering, clearing, paid_orders,
                     liability_diff, cash_diff, unbalanced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    liability = VALUES(liability), metering = VALUES(metering),
                    clearing = VALUES(clearing), paid_orders = VALUES(paid_orders),
                    liability_diff = VALUES(liability_diff), cash_diff = VALUES(cash_diff),
                    unbalanced = VALUES(unbalanced)
                """, tenantId, Date.valueOf(date), summary.liability(), summary.metering(),
                summary.clearing(), summary.paidOrders(), summary.liabilityDiff(),
                summary.cashDiff(), summary.unbalanced());
    }

    private Long firstLong(String sql, Object... args) {
        List<Long> values = jdbc.query(sql, (rs, rowNum) -> rs.getLong(1), args);
        return values.isEmpty() ? null : values.get(0);
    }

    public record UsageRow(long tenantId, long userId, String bizNo, BigDecimal amount, String currency) {
    }

    public record OpeningCredit(long accountId, long tenantId, long userId,
                                BigDecimal balance, String currency) {
    }

    public record OpeningCash(long tenantId, String orderNo, long amountCents) {
    }

    public record TransactionView(long id, String eventKey, String bizType, String bizNo,
                                  String description, LocalDateTime occurredAt,
                                  BigDecimal debit, BigDecimal credit) {
    }
}
